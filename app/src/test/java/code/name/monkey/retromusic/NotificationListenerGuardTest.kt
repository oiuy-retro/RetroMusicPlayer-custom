package code.name.monkey.retromusic

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails if any app code outside the listener file touches notification-reading APIs. */
class NotificationListenerGuardTest {
    private val forbidden = listOf(
        "getActiveNotifications", "getSnoozedNotifications", "getCurrentRanking",
        "cancelNotification", "cancelAllNotifications", "cancelNotifications",
        "snoozeNotification", "setNotificationsShown", "getNotificationChannels",
        "getNotificationChannelGroups", "onNotificationPosted", "onNotificationRemoved",
        "onNotificationRankingUpdate", "StatusBarNotification", "RankingMap",
    )
    private val allowed = "MediaStateListenerService.kt"

    @Test
    fun noNotificationAccessOutsideListener() {
        val root = File("src/main/java")
        assertTrue("source dir not found: ${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.name != allowed }
            .flatMap { f ->
                f.readLines().withIndex().flatMap { (i, line) ->
                    forbidden.filter { Regex("\\b$it\\b").containsMatchIn(line) }
                        .map { "${f.path}:${i + 1} uses $it" }
                }
            }.toList()
        assertTrue("Notification APIs used outside $allowed:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
