package cz.jarda.soundplugin.settings

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import cz.jarda.soundplugin.events.LifecycleEvent
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.events.SoundRequest
import cz.jarda.soundplugin.player.SoundPlayerService
import cz.jarda.soundplugin.soundpack.Soundpack
import cz.jarda.soundplugin.soundpack.SoundpackManager
import cz.jarda.soundplugin.util.SafeRunner
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Swing settings panel (PRD §6.1). Global controls on top; a tabbed mapping table (lifecycle vs
 * IDE actions) in the centre. Edits accumulate in [workingOverrides]; [apply] writes them to
 * [SoundSettingsState]. The (potentially huge) IDE action list is loaded lazily and off the EDT.
 */
class SoundSettingsPanel {

    private val settings = SoundSettingsState.getInstance()
    private val manager = SoundpackManager.getInstance()

    val root: JPanel = JPanel(BorderLayout())

    // --- global controls ---
    private val enabledCheck = JBCheckBox("Enable SoundPlugin")
    private val volumeSlider = JSlider(0, 100)
    private val volumeValueLabel = JBLabel()
    private val packCombo = JComboBox<PackItem>()

    // --- tables ---
    private val lifecycleModel = MappingTableModel(mutableListOf())
    private val actionModel = MappingTableModel(mutableListOf())
    private val lifecycleTable = JBTable(lifecycleModel)
    private val actionTable = JBTable(actionModel)
    private val actionSearch = SearchTextField()
    private var actionSorter: TableRowSorter<MappingTableModel>? = null
    private var actionsLoaded = false

    // --- working state ---
    private val workingOverrides = LinkedHashMap<String, MappingOverride>()
    private var initialEnabled = true
    private var initialVolume = 70
    private var initialPackId = SoundSettingsState.DEFAULT_SOUNDPACK_ID
    private var initialOverridesSnapshot = mapOf<String, MappingOverride>()

    init {
        buildUi()
        reset()
    }

    // ---------------------------------------------------------------- UI build

    private fun buildUi() {
        root.add(buildGlobalPanel(), BorderLayout.NORTH)
        root.add(buildTabs(), BorderLayout.CENTER)
        root.preferredSize = Dimension(720, 520)
    }

    private fun buildGlobalPanel(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        enabledCheck.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(enabledCheck)
        panel.add(Box.createVerticalStrut(6))

        val volRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        volRow.alignmentX = Component.LEFT_ALIGNMENT
        volRow.add(JBLabel("General volume:"))
        volumeSlider.preferredSize = Dimension(220, volumeSlider.preferredSize.height)
        volumeSlider.addChangeListener { updateVolumeLabel() }
        volRow.add(volumeSlider)
        volRow.add(volumeValueLabel)
        panel.add(volRow)
        panel.add(Box.createVerticalStrut(6))

        val packRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        packRow.alignmentX = Component.LEFT_ALIGNMENT
        packRow.add(JBLabel("Active soundpack:"))
        packCombo.addActionListener {
            if (packCombo.selectedItem != null) onPackChanged()
        }
        packRow.add(packCombo)
        panel.add(packRow)
        panel.add(Box.createVerticalStrut(6))

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.alignmentX = Component.LEFT_ALIGNMENT
        buttons.add(button("Import soundpack…") { onImport() })
        buttons.add(button("Export current soundpack…") { onExport() })
        buttons.add(button("Open soundpacks folder") { onOpenFolder() })
        buttons.add(button("Reset to soundpack defaults") { onResetDefaults() })
        panel.add(buttons)

        return panel
    }

    private fun buildTabs(): JComponent {
        val tabs = JTabbedPane()

        configureTable(lifecycleTable, lifecycleModel)
        tabs.addTab("Lifecycle events", JScrollPane(lifecycleTable))

        val actionPanel = JPanel(BorderLayout())
        actionSearch.textEditor.toolTipText = "Filter IDE actions"
        actionSearch.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyActionFilter()
        })
        actionPanel.add(actionSearch, BorderLayout.NORTH)
        configureTable(actionTable, actionModel)
        actionSorter = TableRowSorter(actionModel)
        actionTable.rowSorter = actionSorter
        actionPanel.add(JScrollPane(actionTable), BorderLayout.CENTER)
        tabs.addTab("IDE actions", actionPanel)

        tabs.addChangeListener {
            if (tabs.selectedComponent === actionPanel) ensureActionsLoaded()
        }
        return tabs
    }

    private fun configureTable(table: JBTable, model: MappingTableModel) {
        table.rowHeight = 26
        table.columnModel.getColumn(MappingTableModel.COL_VOLUME).maxWidth = 90
        table.columnModel.getColumn(MappingTableModel.COL_ENABLED).maxWidth = 70
        table.columnModel.getColumn(MappingTableModel.COL_TEST).maxWidth = 90

        // Render the "none" sentinel and test button nicely.
        val soundRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
                val display = if (value == SoundConfigurable.NONE_LABEL) SoundConfigurable.NONE_DISPLAY else value
                return super.getTableCellRendererComponent(t, display, sel, foc, r, c)
            }
        }
        table.columnModel.getColumn(MappingTableModel.COL_SOUND).cellRenderer = soundRenderer
        table.columnModel.getColumn(MappingTableModel.COL_TEST).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(t: JTable, value: Any?, sel: Boolean, foc: Boolean, r: Int, c: Int): Component {
                val c2 = super.getTableCellRendererComponent(t, value, sel, foc, r, c)
                horizontalAlignment = CENTER
                return c2
            }
        }

        // Clicking the Test column previews the row's sound (bypassing global gate & rate limit).
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow < 0 || col != MappingTableModel.COL_TEST) return
                val modelRow = table.convertRowIndexToModel(viewRow)
                playTest(model.rowAt(modelRow))
            }
        })

        // Sync edits back into the working overrides.
        model.addTableModelListener { e ->
            if (e.type == TableModelEvent.UPDATE && e.firstRow >= 0 && e.column != MappingTableModel.COL_TEST) {
                val idx = e.firstRow
                if (idx < model.rowCount) syncRowToOverrides(model.rowAt(idx))
            }
        }
    }

    // ---------------------------------------------------------------- data flow

    private fun activePackId(): String = (packCombo.selectedItem as? PackItem)?.id ?: initialPackId

    private fun activePack(): Soundpack? = manager.getPack(activePackId())

    private fun reloadPackCombo(selectId: String) {
        packCombo.removeAllItems()
        val packs = manager.allPacks()
        for (p in packs) packCombo.addItem(PackItem(p.id, p.name))
        if (packs.none { it.id == selectId } && packs.isNotEmpty()) {
            packCombo.selectedIndex = 0
        } else {
            for (i in 0 until packCombo.itemCount) {
                if (packCombo.getItemAt(i).id == selectId) {
                    packCombo.selectedIndex = i
                    break
                }
            }
        }
    }

    private fun onPackChanged() {
        rebuildLifecycleRows()
        if (actionsLoaded) rebuildActionRows()
        installSoundEditors()
    }

    private fun installSoundEditors() {
        val files = listOf(SoundConfigurable.NONE_LABEL) + (activePack()?.availableSoundFiles ?: emptyList())
        for (table in listOf(lifecycleTable, actionTable)) {
            val combo = JComboBox(files.toTypedArray())
            val base = javax.swing.DefaultListCellRenderer()
            combo.renderer = javax.swing.ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                val display = if (value == SoundConfigurable.NONE_LABEL) SoundConfigurable.NONE_DISPLAY else value
                base.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
            }
            table.columnModel.getColumn(MappingTableModel.COL_SOUND).cellEditor = DefaultCellEditor(combo)
        }
    }

    private fun rebuildLifecycleRows() {
        val pack = activePack()
        val rows = LifecycleEvent.ALL.map { ev ->
            rowFor(ev.id, ev.displayName, isLayerA = false, pack = pack)
        }
        lifecycleModel.setRows(rows)
    }

    private fun rebuildActionRows() {
        val pack = activePack()
        val ids = SafeRunner.compute("listing actions", emptyList<String>()) {
            ActionManager.getInstance().getActionIdList("").sorted()
        }
        val rows = ids.map { id -> rowFor(id, id, isLayerA = true, pack = pack) }
        actionModel.setRows(rows)
        applyActionFilter()
    }

    private fun rowFor(eventId: String, displayName: String, isLayerA: Boolean, pack: Soundpack?): MappingRow {
        val ov = workingOverrides[eventId]
        val def = pack?.defaultMappingFor(eventId)
        val enabled = ov?.enabled ?: (def != null)
        val sound = (ov?.soundFile?.takeIf { it.isNotBlank() } ?: def?.soundFile)?.takeIf { it.isNotBlank() }
            ?: SoundConfigurable.NONE_LABEL
        val volume = (ov?.volume ?: def?.volume ?: 100).coerceIn(0, 100)
        return MappingRow(eventId, displayName, isLayerA, sound, volume, enabled)
    }

    private fun syncRowToOverrides(row: MappingRow) {
        val def = activePack()?.defaultMappingFor(row.eventId)
        val defSound = def?.soundFile?.takeIf { it.isNotBlank() } ?: SoundConfigurable.NONE_LABEL
        val defVolume = def?.volume ?: 100
        val defEnabled = def != null

        val differsSound = row.soundFile != defSound
        val differsVolume = row.volume != defVolume
        val differsEnabled = row.enabled != defEnabled

        if (!differsSound && !differsVolume && !differsEnabled) {
            workingOverrides.remove(row.eventId)
        } else {
            workingOverrides[row.eventId] = MappingOverride(
                soundFile = if (differsSound) row.soundFile else null,
                volume = if (differsVolume) row.volume else null,
                enabled = row.enabled,
            )
        }
    }

    private fun ensureActionsLoaded() {
        if (actionsLoaded) return
        actionsLoaded = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val pack = activePack()
            val rows = SafeRunner.compute("loading action rows", emptyList<MappingRow>()) {
                ActionManager.getInstance().getActionIdList("").sorted()
                    .map { id -> rowFor(id, id, isLayerA = true, pack = pack) }
            }
            ApplicationManager.getApplication().invokeLater({
                actionModel.setRows(rows)
                installSoundEditors()
                applyActionFilter()
            }, ModalityState.any())
        }
    }

    private fun applyActionFilter() {
        val text = actionSearch.text?.trim()?.lowercase().orEmpty()
        actionSorter?.rowFilter = if (text.isEmpty()) null else object : RowFilter<MappingTableModel, Int>() {
            override fun include(entry: Entry<out MappingTableModel, out Int>): Boolean {
                val id = entry.getValue(MappingTableModel.COL_EVENT)?.toString()?.lowercase() ?: ""
                return id.contains(text)
            }
        }
    }

    private fun playTest(row: MappingRow) {
        SafeRunner.run("test playback") {
            if (row.soundFile == SoundConfigurable.NONE_LABEL) return@run
            val pack = activePack() ?: return@run
            val path = pack.resolveSound(row.soundFile)
            val volume = row.volume.coerceIn(0, 100) / 100f
            SoundPlayerService.getInstance().play(SoundRequest(row.eventId, path, volume.coerceAtLeast(0.01f)))
        }
    }

    // ---------------------------------------------------------------- buttons

    private fun onImport() {
        SafeRunner.run("import soundpack") {
            val descriptor = FileChooserDescriptor(true, true, true, true, false, false)
                .withTitle("Import Soundpack")
                .withDescription("Select a .soundpack.zip file or a soundpack folder")
            val chosen = FileChooser.chooseFile(descriptor, null, null) ?: return@run
            val path = java.nio.file.Path.of(chosen.path)
            val staged = (if (chosen.isDirectory) manager.stageImportDir(path) else manager.stageImport(path))
            if (staged == null) {
                Messages.showErrorDialog("No valid soundpack.json found in the selection.", "Import Failed")
                return@run
            }
            var targetId = staged.manifest.id
            if (manager.packExists(targetId)) {
                val choice = Messages.showYesNoCancelDialog(
                    "A soundpack with id '$targetId' already exists. Overwrite it, or import under a new id?",
                    "Soundpack Already Exists", "Overwrite", "Import as new", "Cancel", Messages.getQuestionIcon(),
                )
                when (choice) {
                    Messages.YES -> { /* overwrite: keep targetId */ }
                    Messages.NO -> targetId = manager.suggestFreeId(targetId)
                    else -> return@run
                }
            }
            val installed = manager.installStaged(staged, targetId)
            if (installed == null) {
                Messages.showErrorDialog("Could not install the soundpack.", "Import Failed")
                return@run
            }
            reloadPackCombo(installed.id)
            onPackChanged()
        }
    }

    private fun onExport() {
        SafeRunner.run("export soundpack") {
            val pack = activePack()
            if (pack == null) {
                Messages.showErrorDialog("No active soundpack to export.", "Export Failed")
                return@run
            }
            val saver = FileChooserFactory.getInstance().createSaveFileDialog(
                FileSaverDescriptor("Export Soundpack", "Choose where to save the .soundpack.zip", "zip"), root,
            )
            val wrapper = saver.save(null as com.intellij.openapi.vfs.VirtualFile?, "${pack.id}.soundpack.zip") ?: return@run
            val ok = manager.exportPack(pack, workingOverrides, wrapper.file.toPath())
            if (ok) {
                Messages.showInfoMessage("Exported to ${wrapper.file.absolutePath}", "Export Complete")
            } else {
                Messages.showErrorDialog("Export failed; see idea.log for details.", "Export Failed")
            }
        }
    }

    private fun onOpenFolder() {
        SafeRunner.run("open soundpacks folder") {
            val dir = manager.soundpacksRoot
            java.nio.file.Files.createDirectories(dir)
            RevealFileAction.openDirectory(dir.toFile())
        }
    }

    private fun onResetDefaults() {
        workingOverrides.clear()
        rebuildLifecycleRows()
        if (actionsLoaded) rebuildActionRows()
    }

    // ---------------------------------------------------------------- Configurable contract

    fun isModified(): Boolean {
        if (enabledCheck.isSelected != initialEnabled) return true
        if (volumeSlider.value != initialVolume) return true
        if (activePackId() != initialPackId) return true
        return workingOverrides != initialOverridesSnapshot
    }

    fun apply() {
        settings.enabled = enabledCheck.isSelected
        settings.globalVolume = volumeSlider.value
        settings.activeSoundpackId = activePackId()
        settings.replaceOverrides(workingOverrides)
        // Retry any mappings that had been deactivated and clear debounce state.
        SafeRunner.run("clearing router deactivations") { SoundEventRouter.getInstance().clearDeactivations() }
        snapshotInitialState()
    }

    fun reset() {
        // Idempotent: ensures the bundled pack is unpacked even if settings are opened before the
        // startup activity has run.
        manager.initialize()
        enabledCheck.isSelected = settings.enabled
        volumeSlider.value = settings.globalVolume
        updateVolumeLabel()
        workingOverrides.clear()
        settings.overrides.forEach { (k, v) -> workingOverrides[k] = v.copy() }
        reloadPackCombo(settings.activeSoundpackId)
        rebuildLifecycleRows()
        actionsLoaded = false
        actionModel.setRows(emptyList())
        installSoundEditors()
        snapshotInitialState()
    }

    private fun snapshotInitialState() {
        initialEnabled = enabledCheck.isSelected
        initialVolume = volumeSlider.value
        initialPackId = activePackId()
        initialOverridesSnapshot = workingOverrides.mapValues { it.value.copy() }
    }

    private fun updateVolumeLabel() {
        volumeValueLabel.text = if (volumeSlider.value == 0) "0% (muted)" else "${volumeSlider.value}%"
    }

    // ---------------------------------------------------------------- helpers

    private fun button(text: String, action: () -> Unit): JButton =
        JButton(text).apply { addActionListener { action() } }

    private class PackItem(val id: String, val name: String) {
        override fun toString(): String = "$name ($id)"
    }
}
