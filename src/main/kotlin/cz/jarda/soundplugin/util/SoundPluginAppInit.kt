package cz.jarda.soundplugin.util

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import cz.jarda.soundplugin.events.Events
import cz.jarda.soundplugin.events.SoundEventRouter
import cz.jarda.soundplugin.soundpack.SoundpackManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Eager application-level bootstrap (PRD §11.1). Unpacks the bundled soundpack on first run, forces
 * the router to wire itself to the player, and subscribes the programmatic app-level message-bus
 * topics that cannot be declared in plugin.xml (notifications, VCS update).
 *
 * Instantiated (and thus started) by [SoundPluginStartup] on the first project open. Implements
 * [Disposable] so its bus connection is released when the application shuts down.
 */
@Service
class SoundPluginAppInit : Disposable {

    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        SafeRunner.run("SoundPlugin bootstrap") {
            SoundpackManager.getInstance().initialize()
            // Touch the router so its init wires the player failure callback.
            SoundEventRouter.getInstance()
            subscribeAppBusTopics()
        }
    }

    private fun subscribeAppBusTopics() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)

        SafeRunner.run("subscribing Notifications topic") {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    SafeRunner.run("notification.shown") {
                        val id = when (notification.type) {
                            NotificationType.ERROR -> Events.NOTIFICATION_ERROR
                            NotificationType.WARNING -> Events.NOTIFICATION_WARNING
                            else -> Events.NOTIFICATION_INFO
                        }
                        SoundEventRouter.getInstance().handle(id)
                    }
                }
            })
        }

        SafeRunner.run("subscribing VCS update topic") {
            connection.subscribe(
                com.intellij.openapi.vcs.update.UpdatedFilesListener.UPDATED_FILES,
                com.intellij.openapi.vcs.update.UpdatedFilesListener {
                    SafeRunner.run("vcs.update.completed") {
                        SoundEventRouter.getInstance().handle(Events.VCS_UPDATE_COMPLETED)
                    }
                },
            )
        }
    }

    override fun dispose() {
        // Bus connection is tied to this disposable and released automatically.
    }

    companion object {
        @JvmStatic
        fun getInstance(): SoundPluginAppInit =
            ApplicationManager.getApplication().getService(SoundPluginAppInit::class.java)
    }
}
