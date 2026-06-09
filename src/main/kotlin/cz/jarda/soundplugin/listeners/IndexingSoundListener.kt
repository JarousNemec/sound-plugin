package cz.jarda.soundplugin.listeners

import com.intellij.openapi.project.DumbService
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Fires when the IDE leaves dumb mode, i.e. indexing finished (PRD §5.2). */
class IndexingSoundListener : DumbService.DumbModeListener {
    override fun exitDumbMode() {
        SafeRunner.run("indexing.finished") {
            SoundEventRouter.getInstance().handle(Events.INDEXING_FINISHED)
        }
    }
}
