package cz.jarda.soundplugin.listeners

import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Fires [Events.FILE_SAVED] when documents are about to be saved (PRD §5.2). */
class FileSavedSoundListener : FileDocumentManagerListener {
    override fun beforeAllDocumentsSaving() {
        SafeRunner.run("file.saved") {
            SoundEventRouter.getInstance().handle(Events.FILE_SAVED)
        }
    }
}
