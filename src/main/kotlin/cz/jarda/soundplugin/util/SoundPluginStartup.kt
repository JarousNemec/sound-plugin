package cz.jarda.soundplugin.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Per-project startup hook (registered as a `postStartupActivity`). Its only job is to make sure
 * the eager application bootstrap has run; the heavy lifting lives in [SoundPluginAppInit] and is
 * idempotent, so running this on every project open is harmless.
 */
class SoundPluginStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        SafeRunner.run("SoundPluginStartup") {
            SoundPluginAppInit.getInstance().ensureStarted()
        }
    }
}
