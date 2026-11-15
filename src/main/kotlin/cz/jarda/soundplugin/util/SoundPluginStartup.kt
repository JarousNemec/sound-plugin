package cz.jarda.soundplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter

/**
 * Per-project startup hook (registered as a `postStartupActivity`, runs off the EDT). Makes sure
 * the application bootstrap has run (idempotent), then fires [Events.PROJECT_OPENED].
 *
 * `project.opened` is fired here rather than from the deprecated
 * `ProjectManagerListener.projectOpened`, which newer IDEs no longer call reliably. Here the
 * soundpacks are guaranteed to be loaded, so even the first project after IDE start plays.
 */
class SoundPluginStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        SafeRunner.run("SoundPluginStartup") {
            SoundPluginAppInit.getInstance().ensureStarted("project '${project.name}' opened")
            SoundEventRouter.getInstance().handle(Events.PROJECT_OPENED)
        }
    }
}
