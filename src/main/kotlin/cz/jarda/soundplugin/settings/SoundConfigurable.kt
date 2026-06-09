package cz.jarda.soundplugin.settings

import com.intellij.openapi.options.SearchableConfigurable
import cz.jarda.soundplugin.events.SoundEventRouter
import javax.swing.JComponent

/**
 * Settings page under Tools → SoundPlugin (PRD §6). Thin wrapper delegating to [SoundSettingsPanel].
 */
class SoundConfigurable : SearchableConfigurable {

    private var panel: SoundSettingsPanel? = null

    override fun getId(): String = "cz.jarda.soundplugin.settings"
    override fun getDisplayName(): String = "SoundPlugin"

    override fun createComponent(): JComponent {
        val p = panel ?: SoundSettingsPanel().also { panel = it }
        return p.root
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }

    companion object {
        /** Sentinel value for the "no sound" choice; shared with the router. */
        const val NONE_LABEL: String = SoundEventRouter.NONE
        const val NONE_DISPLAY: String = "— none —"
    }
}
