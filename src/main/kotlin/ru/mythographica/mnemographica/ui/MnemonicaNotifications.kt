package ru.mythographica.mnemographica.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project

object MnemonicaNotifications {
    private const val GROUP_ID = "Mnemonica"

    fun notify(
        project: Project,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
        actions: List<AnAction> = emptyList(),
        onExpired: (() -> Unit)? = null,
    ): Notification {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Mnemonica", content, type)
        actions.forEach(notification::addAction)
        onExpired?.let { notification.whenExpired(it) }
        notification.notify(project)
        return notification
    }
}
