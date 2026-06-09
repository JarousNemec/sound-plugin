package cz.jarda.soundplugin.events

/**
 * Stable identifier of a sound-triggering event.
 *
 * Layer B (lifecycle) ids are the fixed, curated set declared in [Events]. Layer A (IDE actions)
 * ids are dynamic and unbounded — they are whatever [com.intellij.openapi.actionSystem.ActionManager]
 * returns for a given action — so the type is a thin wrapper over a string rather than an enum.
 */
@JvmInline
value class EventId(val raw: String) {
    override fun toString(): String = raw
}

/**
 * Catalog of the curated Layer-B lifecycle event ids, plus the documented Layer-A examples.
 * Layer-A ids in general are read live from `ActionManager`; the constants here are only the
 * decent default subset highlighted in the UI / shipped enabled.
 */
object Events {
    // ---- Layer B: lifecycle events (finite, curated) ----
    const val BUILD_SUCCESS = "build.finished.success"
    const val BUILD_FAILED = "build.finished.failed"
    const val RUN_STARTED = "run.process.started"
    const val RUN_TERMINATED = "run.process.terminated"
    const val RUN_TERMINATED_SUCCESS = "run.process.terminated.success"
    const val DEBUG_BREAKPOINT_HIT = "debug.breakpoint.hit"
    const val TEST_PASSED = "test.finished.passed"
    const val TEST_FAILED = "test.finished.failed"
    const val VCS_COMMIT_COMPLETED = "vcs.commit.completed"
    const val VCS_UPDATE_COMPLETED = "vcs.update.completed"
    const val INDEXING_FINISHED = "indexing.finished"
    const val NOTIFICATION_INFO = "notification.shown.info"
    const val NOTIFICATION_WARNING = "notification.shown.warning"
    const val NOTIFICATION_ERROR = "notification.shown.error"
    const val FILE_SAVED = "file.saved"
    const val PROJECT_OPENED = "project.opened"
    const val PROJECT_CLOSED = "project.closed"
    const val APP_FRAME_ACTIVATED = "app.frame.activated"

    /** Documented examples of Layer-A action ids (the full list comes from ActionManager). */
    val SAMPLE_ACTION_IDS: List<String> = listOf(
        "\$Copy", "\$Paste", "EditorDeleteLine", "Build", "Run", "Stop",
        "CommentByLineComment", "FindInPath", "RenameElement", "SaveAll", "ReformatCode",
    )
}
