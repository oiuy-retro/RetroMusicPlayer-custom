package code.name.monkey.retromusic.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Exists only so the user can grant notification access, which Android requires before an app
 * may read other apps' media session playback state (MediaSessionService checks only that the
 * listener is enabled in settings, not that it's bound).
 *
 * Hardened so it can never read notifications:
 * - It unbinds itself as soon as it connects, so the system stops delivering notifications.
 * - Every notification-reading method it can override throws a SecurityException.
 * - Notification callbacks the system might still deliver before the unbind are ignored
 *   without touching their contents (throwing there would crash playback).
 * Final methods (cancel*, snoozeNotification, getSnoozedNotifications, ...) can't be overridden;
 * NotificationListenerGuardTest fails the build's tests if app code calls them.
 */
class MediaStateListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        ResumeLog.init(this)
        ResumeLog.d("listener connected, unbinding")
        requestUnbind()
    }

    private fun blocked(name: String): Nothing =
        throw SecurityException("Retro must never read notifications ($name is blocked)")

    override fun getActiveNotifications(): Array<StatusBarNotification> =
        blocked("getActiveNotifications")

    override fun getActiveNotifications(keys: Array<out String>?): Array<StatusBarNotification> =
        blocked("getActiveNotifications")

    override fun getCurrentRanking(): RankingMap = blocked("getCurrentRanking")

    // System callbacks: ignored, contents never read.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {}
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int
    ) {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {}
    override fun onNotificationChannelModified(
        pkg: String?, user: UserHandle?, channel: NotificationChannel?, modificationType: Int
    ) {}
    override fun onNotificationChannelGroupModified(
        pkg: String?, user: UserHandle?, group: NotificationChannelGroup?, modificationType: Int
    ) {}
}
