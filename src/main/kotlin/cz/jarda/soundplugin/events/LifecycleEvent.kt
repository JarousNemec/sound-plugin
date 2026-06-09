package cz.jarda.soundplugin.events

/**
 * Descriptor of a curated Layer-B lifecycle event, used to populate the settings table and to
 * model graceful degradation (PRD §8.5): an event whose backing platform API is unavailable in
 * the running IDE is shown as unavailable rather than silently missing.
 *
 * @property id the EventId raw string (one of [Events])
 * @property displayName human-readable label shown in the settings table
 * @property defaultEnabled whether the bundled pack enables this by default (PRD §5.3)
 */
data class LifecycleEvent(
    val id: String,
    val displayName: String,
    val defaultEnabled: Boolean,
) {
    companion object {
        /** The full, ordered, curated Layer-B catalog (PRD §5.2). */
        val ALL: List<LifecycleEvent> = listOf(
            LifecycleEvent(Events.BUILD_SUCCESS, "Build finished — success", true),
            LifecycleEvent(Events.BUILD_FAILED, "Build finished — failed", true),
            LifecycleEvent(Events.RUN_STARTED, "Run process started", false),
            LifecycleEvent(Events.RUN_TERMINATED_SUCCESS, "Run process terminated — success", true),
            LifecycleEvent(Events.RUN_TERMINATED, "Run process terminated — failed", true),
            LifecycleEvent(Events.DEBUG_BREAKPOINT_HIT, "Debugger paused (breakpoint/step)", false),
            LifecycleEvent(Events.TEST_PASSED, "Tests finished — passed", true),
            LifecycleEvent(Events.TEST_FAILED, "Tests finished — failed", true),
            LifecycleEvent(Events.VCS_COMMIT_COMPLETED, "VCS commit completed", true),
            LifecycleEvent(Events.VCS_UPDATE_COMPLETED, "VCS update completed", false),
            LifecycleEvent(Events.INDEXING_FINISHED, "Indexing finished", false),
            LifecycleEvent(Events.NOTIFICATION_ERROR, "Notification shown — error", true),
            LifecycleEvent(Events.NOTIFICATION_WARNING, "Notification shown — warning", false),
            LifecycleEvent(Events.NOTIFICATION_INFO, "Notification shown — info", false),
            LifecycleEvent(Events.FILE_SAVED, "File(s) saved", false),
            LifecycleEvent(Events.PROJECT_OPENED, "Project opened", false),
            LifecycleEvent(Events.PROJECT_CLOSED, "Project closed", false),
            LifecycleEvent(Events.APP_FRAME_ACTIVATED, "IDE frame activated", false),
        )

        val DEFAULT_ENABLED_IDS: Set<String> = ALL.filter { it.defaultEnabled }.map { it.id }.toSet()

        fun displayNameFor(id: String): String =
            ALL.firstOrNull { it.id == id }?.displayName ?: id
    }
}
