package cz.jarda.soundplugin.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Fires project open/close events (PRD §5.2). */
class ProjectLifecycleSoundListener : ProjectManagerListener {
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun projectOpened(project: Project) {
        SafeRunner.run("project.opened") {
            SoundEventRouter.getInstance().handle(Events.PROJECT_OPENED)
        }
    }

    override fun projectClosed(project: Project) {
        SafeRunner.run("project.closed") {
            SoundEventRouter.getInstance().handle(Events.PROJECT_CLOSED)
        }
    }
}
