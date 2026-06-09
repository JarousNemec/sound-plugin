package cz.jarda.soundplugin.listeners

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.AnActionListener
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/**
 * Layer A (PRD §5.1): catches every IDE action after it runs and routes its stable action id as
 * an EventId. Registered as an application listener. The router decides whether a given action is
 * actually mapped to a sound — by default only a small curated subset is.
 *
 * Runs on the EDT, so it does the absolute minimum: resolve the id and hand off. No I/O, no audio.
 */
class ActionSoundListener : AnActionListener {

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: com.intellij.openapi.actionSystem.AnActionResult) {
        SafeRunner.run("ActionSoundListener.afterActionPerformed") {
            val id = ActionManager.getInstance().getId(action) ?: return@run
            SoundEventRouter.getInstance().handle(id)
        }
    }
}
