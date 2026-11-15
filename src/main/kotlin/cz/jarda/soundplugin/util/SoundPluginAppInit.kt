package cz.jarda.soundplugin.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.player.SoundPlayerService
import cz.jarda.soundplugin.settings.SoundSettingsState
import cz.jarda.soundplugin.soundpack.SoundpackManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level bootstrap (PRD §11.1). Unpacks and loads the soundpacks, wires the router to
 * the player, subscribes the programmatic app-level message-bus topics that cannot be declared in
 * plugin.xml (notifications, VCS update), warms up the audio device and logs a startup summary.
 *
 * Normally started by [SoundPluginStartup] on the first project open. A plugin installed without
 * an IDE restart may not get that call for already-open projects, so the router also triggers
 * [ensureStartedAsync] on the first event. Implements [Disposable] so its bus connection is
 * released when the application shuts down.
 */
@Service
class SoundPluginAppInit : Disposable {

    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    /**
     * Runs the bootstrap on the calling thread, or waits (bounded) for one already in progress.
     * Does disk I/O; never call it on the EDT.
     */
    fun ensureStarted(trigger: String) {
        if (!started.compareAndSet(false, true)) {
            finished.await(5, TimeUnit.SECONDS)
            return
        }
        bootstrap(trigger)
    }

    /** Cheap no-op once started; otherwise runs the bootstrap on a pooled thread. */
    fun ensureStartedAsync(trigger: String) {
        if (started.get()) return
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { bootstrap(trigger) }
    }

    private fun bootstrap(trigger: String) {
        val start = System.nanoTime()
        SafeRunner.run("SoundPlugin bootstrap") {
            SoundLog.info("starting (trigger: $trigger) in ${ApplicationInfo.getInstance().build}")
            val manager = SoundpackManager.getInstance()
            manager.initialize()
            // Touch the router so its init wires the player failure callback.
            SoundEventRouter.getInstance()
            subscribeAppBusTopics()
            SoundPlayerService.getInstance().warmUp()
            logSummary(manager, SoundLog.msSince(start))
        }
        finished.countDown()
    }

    private fun logSummary(manager: SoundpackManager, tookMs: String) {
        SafeRunner.run("startup summary") {
            val settings = SoundSettingsState.getInstance()
            val packs = manager.allPacks().joinToString { "'${it.id}' v${it.manifest.version} (${it.manifest.defaultMappings.size} mappings)" }
            val active = manager.getActive(settings.activeSoundpackId)
            val activeNote = when {
                active == null -> "NONE - nothing will play"
                active.id != settings.activeSoundpackId -> "'${active.id}' (configured '${settings.activeSoundpackId}' not found)"
                else -> "'${active.id}'"
            }
            SoundLog.info(
                "started in $tookMs ms: packs [$packs] in ${manager.soundpacksRoot}; active $activeNote; " +
                    "enabled=${settings.enabled}, volume=${settings.globalVolume} %, overrides=${settings.overrides.size}, " +
                    "verbose logging=${SoundLog.isVerbose}",
            )
        }
    }

    private fun subscribeAppBusTopics() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)

        SafeRunner.run("subscribing Notifications topic") {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    SafeRunner.run("notification.shown") {
                        val id = when (notification.type) {
                            NotificationType.ERROR -> Events.NOTIFICATION_ERROR
                            NotificationType.WARNING -> Events.NOTIFICATION_WARNING
                            else -> Events.NOTIFICATION_INFO
                        }
                        SoundLog.trace { "notification (${notification.type}) from group '${notification.groupId}': '${notification.title}'" }
                        SoundEventRouter.getInstance().handle(id)
                    }
                }
            })
        }

        SafeRunner.run("subscribing VCS update topic") {
            connection.subscribe(
                com.intellij.openapi.vcs.update.UpdatedFilesListener.UPDATED_FILES,
                com.intellij.openapi.vcs.update.UpdatedFilesListener {
                    SafeRunner.run("vcs.update.completed") {
                        SoundEventRouter.getInstance().handle(Events.VCS_UPDATE_COMPLETED)
                    }
                },
            )
        }
    }

    override fun dispose() {
        // Bus connection is tied to this disposable and released automatically.
    }

    companion object {
        @JvmStatic
        fun getInstance(): SoundPluginAppInit =
            ApplicationManager.getApplication().getService(SoundPluginAppInit::class.java)
    }
}
