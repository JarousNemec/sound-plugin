package cz.jarda.soundplugin.listeners

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.util.SafeRunner

/**
 * Registered via the `checkinHandlerFactory` extension point (base platform). Contributes a
 * [CheckinHandler] whose [CheckinHandler.checkinSuccessful] fires [Events.VCS_COMMIT_COMPLETED]
 * (PRD §5.2). Contributes nothing when no VCS is configured — natural graceful degradation.
 */
class VcsCommitHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return object : CheckinHandler() {
            override fun checkinSuccessful() {
                SafeRunner.run("vcs.commit.completed") {
                    SoundEventRouter.getInstance().handle(Events.VCS_COMMIT_COMPLETED)
                }
            }
        }
    }
}
