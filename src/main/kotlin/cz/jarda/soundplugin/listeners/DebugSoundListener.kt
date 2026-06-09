package cz.jarda.soundplugin.listeners

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebugSessionListener
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/**
 * There is no dedicated "breakpoint hit" topic in the platform. We listen for debug processes
 * starting, then attach a session listener whose [XDebugSessionListener.sessionPaused] fires on any
 * pause (breakpoint or step) — close enough for an audio cue (PRD §5.2). Off by default.
 */
class DebugSoundListener : XDebuggerManagerListener {
    override fun processStarted(debugProcess: XDebugProcess) {
        SafeRunner.run("debug processStarted") {
            debugProcess.session.addSessionListener(object : XDebugSessionListener {
                override fun sessionPaused() {
                    SafeRunner.run("debug.breakpoint.hit") {
                        SoundEventRouter.getInstance().handle(Events.DEBUG_BREAKPOINT_HIT)
                    }
                }
            })
        }
    }
}
