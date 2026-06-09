package cz.jarda.soundplugin.listeners

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/**
 * Build (compilation) events (PRD §5.2). Lives in the Java plugin's API, so this listener is only
 * registered when `com.intellij.java` is present (see soundplugin-java.xml) — graceful degradation
 * for non-Java IDEs. Aborted builds are ignored; otherwise errors > 0 → failed, else success.
 */
class BuildSoundListener : CompilationStatusListener {
    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        SafeRunner.run("build.finished") {
            if (aborted) return@run
            val id = if (errors > 0) Events.BUILD_FAILED else Events.BUILD_SUCCESS
            SoundEventRouter.getInstance().handle(id)
        }
    }
}
