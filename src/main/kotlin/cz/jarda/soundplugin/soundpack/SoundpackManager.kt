package cz.jarda.soundplugin.soundpack

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.util.SafeRunner
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Loads, validates and manages soundpacks (PRD §7). Bundled packs live on the plugin classpath
 * and are unpacked into the user config dir on first run so they are editable and serve as a
 * template. All disk/JSON work is error-isolated; a malformed pack never breaks the others.
 */
@Service
class SoundpackManager {

    private val log = Logger.getInstance(SoundpackManager::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Root under which every installed pack lives: `<config>/SoundPlugin/soundpacks/<id>/`. */
    var soundpacksRoot: Path = PathManager.getConfigDir().resolve("SoundPlugin").resolve("soundpacks")

    private val loaded = LinkedHashMap<String, Soundpack>()

    /** Ensures the bundled pack is on disk, then loads every pack found under [soundpacksRoot]. */
    @Synchronized
    fun initialize() {
        SafeRunner.run("unpacking bundled soundpacks") { unpackBundledIfNeeded() }
        reload()
    }

    @Synchronized
    fun reload() {
        loaded.clear()
        SafeRunner.run("loading soundpacks") {
            if (!soundpacksRoot.isDirectory()) return@run
            Files.list(soundpacksRoot).use { stream ->
                stream.filter { it.isDirectory() }.forEach { dir ->
                    val pack = loadPack(dir)
                    if (pack != null) loaded[pack.id] = pack
                }
            }
        }
    }

    @Synchronized
    fun allPacks(): List<Soundpack> = loaded.values.toList()

    @Synchronized
    fun getPack(id: String): Soundpack? = loaded[id]

    /** The active pack per settings, falling back to the bundled pack, then any pack. */
    @Synchronized
    fun getActive(activeId: String): Soundpack? =
        loaded[activeId] ?: loaded[DEFAULT_PACK_ID] ?: loaded.values.firstOrNull()

    private fun loadPack(dir: Path): Soundpack? {
        val manifestFile = dir.resolve(Soundpack.MANIFEST_FILE)
        if (!manifestFile.exists()) {
            log.warn("SoundPlugin: skipping '${dir.name}' — no ${Soundpack.MANIFEST_FILE}")
            return null
        }
        return SafeRunner.compute("parsing manifest in ${dir.name}", null) {
            val manifest = Files.newBufferedReader(manifestFile).use { gson.fromJson(it, SoundpackManifest::class.java) }
                ?: return@compute null
            if (manifest.schemaVersion > SoundpackManifest.CURRENT_SCHEMA_VERSION) {
                log.warn("SoundPlugin: pack '${manifest.id}' schemaVersion ${manifest.schemaVersion} is newer than supported; loading best-effort")
            }
            if (manifest.id.isBlank()) {
                log.warn("SoundPlugin: pack in '${dir.name}' has a blank id; skipping")
                return@compute null
            }
            validateMappings(manifest, dir)
            Soundpack(manifest, dir)
        }
    }

    /**
     * Drops mappings that reference a missing or unsupported sound file (PRD §9), with a warning
     * each. Unknown lifecycle eventIds are flagged with a warning but kept — a mapping for an
     * eventId this version does not fire is simply inert, and dropping it would silently lose
     * forward-compatible or action (Layer-A) mappings on a round-trip (PRD §7.2). Never throws.
     */
    private fun validateMappings(manifest: SoundpackManifest, root: Path) {
        val iterator = manifest.defaultMappings.iterator()
        while (iterator.hasNext()) {
            val m = iterator.next()
            // Warn (but keep) only for lifecycle-namespace ids we don't recognise; Layer-A action
            // ids are arbitrary strings and must not be flagged.
            if (looksLikeLifecycleId(m.eventId) && m.eventId !in KNOWN_EVENT_IDS) {
                log.warn("SoundPlugin: pack '${manifest.id}' references unknown lifecycle eventId '${m.eventId}'; keeping but it will not fire")
            }
            val file = root.resolve(m.soundFile).normalize()
            val ok = SafeRunner.compute("validating ${m.soundFile}", false) {
                file.exists() && Soundpack.isSupportedAudio(file) && AudioSystem.getAudioFileFormat(file.toFile()) != null
            }
            if (!ok) {
                log.warn("SoundPlugin: pack '${manifest.id}' mapping for '${m.eventId}' has missing/unsupported file '${m.soundFile}'; ignoring")
                iterator.remove()
            }
        }
    }

    /** Lifecycle ids use a dotted namespace we own; action ids never start with these prefixes. */
    private fun looksLikeLifecycleId(eventId: String): Boolean =
        LIFECYCLE_PREFIXES.any { eventId.startsWith(it) }

    // ---- bundled pack unpacking ----

    private fun unpackBundledIfNeeded() {
        for (bundled in BUNDLED_PACKS) {
            SafeRunner.run("unpacking bundled pack ${bundled.id}") { unpackBundled(bundled) }
        }
    }

    /**
     * Copies one bundled pack from the plugin classpath into [soundpacksRoot]. A jar cannot be
     * listed, so the files to copy are taken from the bundled manifest's mappings. When the
     * installed manifest's `version` differs from the bundled one, the manifest and all bundled
     * sound files are overwritten so plugin updates reach existing installs; otherwise only
     * missing files are restored and user edits are kept.
     */
    private fun unpackBundled(bundled: BundledPack) {
        val resourceDir = "/soundpacks/${bundled.id}"
        val manifestBytes = javaClass.getResourceAsStream("$resourceDir/${Soundpack.MANIFEST_FILE}")?.use { it.readBytes() }
            ?: error("bundled $resourceDir/${Soundpack.MANIFEST_FILE} missing from plugin classpath")
        val bundledManifest = gson.fromJson(String(manifestBytes, Charsets.UTF_8), SoundpackManifest::class.java)

        val target = soundpacksRoot.resolve(bundled.id)
        val manifestTarget = target.resolve(Soundpack.MANIFEST_FILE)
        val installedVersion = if (manifestTarget.exists()) {
            SafeRunner.compute("reading installed manifest of ${bundled.id}", null) {
                Files.newBufferedReader(manifestTarget).use { gson.fromJson(it, SoundpackManifest::class.java) }?.version
            }
        } else null
        val upToDate = installedVersion == bundledManifest.version

        if (!upToDate) {
            if (installedVersion != null) {
                log.info("SoundPlugin: updating bundled pack '${bundled.id}' $installedVersion -> ${bundledManifest.version}")
            }
            Files.createDirectories(target)
            Files.write(manifestTarget, manifestBytes)
        }

        for (soundFile in bundledManifest.defaultMappings.map { it.soundFile }.distinct()) {
            val file = target.resolve(soundFile).normalize()
            if (!file.startsWith(target)) continue
            if (upToDate && file.exists()) continue
            // Packs with generated placeholders (stronghold) ship no audio in resources.
            val resource = javaClass.getResourceAsStream("$resourceDir/$soundFile") ?: continue
            SafeRunner.run("unpacking $soundFile of ${bundled.id}") {
                resource.use {
                    Files.createDirectories(file.parent)
                    Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        if (bundled.generatePlaceholderTones) {
            // (Re)generate any missing placeholder tones referenced by the pack.
            val soundsDir = target.resolve(Soundpack.SOUNDS_DIR)
            for (tone in ToneGenerator.BUNDLED_TONES) {
                val file = soundsDir.resolve(tone.fileName)
                if (!file.exists()) {
                    SafeRunner.run("generating tone ${tone.fileName}") { ToneGenerator.write(tone, file) }
                }
            }
        }
    }

    /** A soundpack shipped inside the plugin under `/soundpacks/<id>/`. */
    private class BundledPack(val id: String, val generatePlaceholderTones: Boolean)

    // ---- import ----

    /** A staged (extracted + parsed) import awaiting an install decision. */
    class StagedImport(val manifest: SoundpackManifest, val sourceDir: Path)

    /**
     * Extracts a `.soundpack.zip` and parses its manifest without installing it yet, so the caller
     * (settings UI) can resolve an id collision first. Returns null if the archive has no valid
     * manifest.
     */
    fun stageImport(zip: Path): StagedImport? = SafeRunner.compute("staging import", null) {
        val dir = SoundpackIO.unzipAndLocateManifest(zip) ?: return@compute null
        val manifestFile = dir.resolve(Soundpack.MANIFEST_FILE)
        val manifest = Files.newBufferedReader(manifestFile).use { gson.fromJson(it, SoundpackManifest::class.java) }
            ?: return@compute null
        if (manifest.id.isBlank()) return@compute null
        StagedImport(manifest, dir)
    }

    /** Stages a plain (unzipped) pack directory. */
    fun stageImportDir(dir: Path): StagedImport? = SafeRunner.compute("staging dir import", null) {
        val manifestDir = SoundpackIO.findManifestDir(dir) ?: return@compute null
        val manifest = Files.newBufferedReader(manifestDir.resolve(Soundpack.MANIFEST_FILE))
            .use { gson.fromJson(it, SoundpackManifest::class.java) } ?: return@compute null
        if (manifest.id.isBlank()) return@compute null
        StagedImport(manifest, manifestDir)
    }

    fun packExists(id: String): Boolean = soundpacksRoot.resolve(id).resolve(Soundpack.MANIFEST_FILE).exists()

    /** Suggests a free id derived from [baseId] (baseId-2, baseId-3, …). */
    fun suggestFreeId(baseId: String): String {
        if (!packExists(baseId)) return baseId
        var i = 2
        while (packExists("$baseId-$i")) i++
        return "$baseId-$i"
    }

    /**
     * Installs a previously [stageImport]ed pack under [targetId] (overwriting that id if present),
     * rewriting the manifest id to match, then reloads. Returns the installed pack, or null.
     */
    @Synchronized
    fun installStaged(staged: StagedImport, targetId: String): Soundpack? =
        SafeRunner.compute("installing soundpack", null) {
            val target = soundpacksRoot.resolve(targetId)
            if (target.exists()) target.toFile().deleteRecursively()
            Files.createDirectories(target)
            SoundpackIO.copyDirectory(staged.sourceDir, target)
            staged.manifest.id = targetId
            writeManifest(staged.manifest, target)
            reload()
            loaded[targetId]
        }

    // ---- export ----

    /**
     * Exports [pack] merged with the user's [overrides] into `<name>.soundpack.zip` at [targetZip]
     * (PRD §7.3). Only soundFile/volume are written; no personal IDE state. Disabled mappings are
     * omitted.
     */
    fun exportPack(pack: Soundpack, overrides: Map<String, cz.jarda.soundplugin.settings.MappingOverride>, targetZip: Path): Boolean =
        SafeRunner.compute("exporting soundpack", false) {
            val effective = buildEffectiveManifest(pack, overrides)
            SoundpackIO.zipPack(pack.root, gson.toJson(effective), targetZip)
            true
        }

    private fun buildEffectiveManifest(
        pack: Soundpack,
        overrides: Map<String, cz.jarda.soundplugin.settings.MappingOverride>,
    ): SoundpackManifest {
        val src = pack.manifest
        val out = SoundpackManifest().apply {
            schemaVersion = src.schemaVersion
            id = src.id
            name = src.name
            author = src.author
            description = src.description
            version = src.version
        }
        val eventIds = LinkedHashSet<String>()
        src.defaultMappings.forEach { eventIds.add(it.eventId) }
        eventIds.addAll(overrides.keys)
        for (eventId in eventIds) {
            val ov = overrides[eventId]
            if (ov != null && !ov.enabled) continue
            val def = src.defaultMappingFor(eventId)
            val soundFile = (ov?.soundFile?.takeIf { it.isNotBlank() } ?: def?.soundFile)?.takeIf { it.isNotBlank() } ?: continue
            val volume = (ov?.volume ?: def?.volume ?: 100).coerceIn(0, 100)
            out.defaultMappings.add(DefaultMapping(eventId, soundFile, volume))
        }
        return out
    }

    private fun writeManifest(manifest: SoundpackManifest, dir: Path) {
        Files.newBufferedWriter(dir.resolve(Soundpack.MANIFEST_FILE)).use { gson.toJson(manifest, it) }
    }

    companion object {
        /** The bundled pack active by default (rendered MIDI cues for every event). */
        const val DEFAULT_PACK_ID = "demo-midi"

        /** The bundled Stronghold pack; until real samples exist it holds generated placeholder tones. */
        const val STRONGHOLD_PACK_ID = "stronghold-classic"

        private val BUNDLED_PACKS = listOf(
            BundledPack(DEFAULT_PACK_ID, generatePlaceholderTones = false),
            BundledPack(STRONGHOLD_PACK_ID, generatePlaceholderTones = true),
        )

        private val LIFECYCLE_PREFIXES = listOf(
            "build.", "run.", "debug.", "test.", "vcs.", "indexing.", "notification.",
            "file.", "project.", "app.",
        )

        private val KNOWN_EVENT_IDS: Set<String> = setOf(
            Events.BUILD_SUCCESS, Events.BUILD_FAILED, Events.RUN_STARTED, Events.RUN_TERMINATED,
            Events.RUN_TERMINATED_SUCCESS, Events.DEBUG_BREAKPOINT_HIT, Events.TEST_PASSED,
            Events.TEST_FAILED, Events.VCS_COMMIT_COMPLETED, Events.VCS_UPDATE_COMPLETED,
            Events.INDEXING_FINISHED, Events.NOTIFICATION_INFO, Events.NOTIFICATION_WARNING,
            Events.NOTIFICATION_ERROR, Events.FILE_SAVED, Events.PROJECT_OPENED,
            Events.PROJECT_CLOSED, Events.APP_FRAME_ACTIVATED,
        )

        @JvmStatic
        fun getInstance(): SoundpackManager =
            ApplicationManager.getApplication().getService(SoundpackManager::class.java)
    }
}
