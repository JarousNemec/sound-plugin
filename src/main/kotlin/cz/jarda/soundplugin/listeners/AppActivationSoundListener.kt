package cz.jarda.soundplugin.listeners

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Fires when the IDE frame regains focus (PRD §5.2). Off by default — potentially noisy. */
class AppActivationSoundListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        SafeRunner.run("app.frame.activated") {
            SoundEventRouter.getInstance().handle(Events.APP_FRAME_ACTIVATED)
        }
    }
}
