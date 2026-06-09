package cz.jarda.soundplugin.listeners

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/** Fires when a test run finishes (PRD §5.2). A defective root → failed, otherwise passed. */
class TestSoundListener : SMTRunnerEventsAdapter() {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        SafeRunner.run("test.finished") {
            val id = if (testsRoot.isDefect) Events.TEST_FAILED else Events.TEST_PASSED
            SoundEventRouter.getInstance().handle(id)
        }
    }
}
