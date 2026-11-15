package cz.jarda.soundplugin.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/**
 * Fires [Events.PROJECT_CLOSED] (PRD §5.2). `project.opened` is fired by
 * [cz.jarda.soundplugin.util.SoundPluginStartup] instead of the deprecated `projectOpened` callback.
 */
class ProjectLifecycleSoundListener : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        SafeRunner.run("project.closed") {
            SoundEventRouter.getInstance().handle(Events.PROJECT_CLOSED)
        }
    }
}
