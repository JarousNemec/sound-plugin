package cz.jarda.soundplugin.listeners

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Run/Debug process lifecycle (PRD §5.2). Exit code 0 → terminated.success, otherwise failed. */
class ExecutionSoundListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        SafeRunner.run("run.process.started") {
            SoundEventRouter.getInstance().handle(Events.RUN_STARTED)
        }
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        SafeRunner.run("run.process.terminated") {
            val id = if (exitCode == 0) Events.RUN_TERMINATED_SUCCESS else Events.RUN_TERMINATED
            SoundEventRouter.getInstance().handle(id)
        }
    }
}
