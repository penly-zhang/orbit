package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import dev.codexpocket.core.*
import kotlinx.serialization.json.JsonPrimitive

/** Explicit device probe: enables requested reminders and posts synthetic events, without running an agent. */
internal class TaskNotificationProbe(private val runner: Instrumentation) {
    fun run() {
        val result = Bundle()
        val manager = runner.targetContext.getSystemService(NotificationManager::class.java)
        val marker = "notification-probe-${System.currentTimeMillis()}"
        try {
            val activity = runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
            runner.runOnMainSync {
                val runtime = PocketRuntime.get(activity.application)
                runtime.update { it.copy(background = true, notifications = true) }
                activity.moveTaskToBack(true)
            }
            Thread.sleep(1000)
            val tab = SessionTab(ThreadInfo(marker, "通知验证", "/example"), ProjectInfo("example", "通知验证", listOf("/example")),
                1, profileId = marker, profileName = "Orbit", tracked = true, taskReady = true)
            val before = PocketState(sessionTabs = listOf(tab), connections = mapOf(marker to HostConnectionStatus(connected = true)))
            val done = before.copy(sessionTabs = listOf(tab.copy(progress = TaskProgress(marker, "completed"))))
            val question = before.copy(pending = listOf(PendingRequest(JsonPrimitive(marker), RequestKind.QUESTIONS, obj("threadId" to marker), marker)))
            runner.runOnMainSync {
                val notices = TaskNotices(activity.application)
                notices.changed(before, done)
                notices.changed(before, question)
                notices.changed(before, done)
            }
            Thread.sleep(1000)
            val posted = manager.activeNotifications.filter { it.tag?.contains(marker) == true }
            check(posted.size == 2) { "Expected completion and question notifications, got ${posted.size}" }
            check(posted.all { it.notification.contentIntent != null && it.notification.channelId == "tasks" })
            check(posted.map { it.notification.extras.getString("android.title") }.toSet() == setOf("本轮已结束", "Agent 需要你的回答"))
            result.putString("stream", "PASS: background completion/question notifications, deduplication and content intents; channel importance=${manager.getNotificationChannel("tasks").importance}\n")
            // Leave time to inspect the real system banner; remove only our synthetic notifications.
            Thread.sleep(5000)
        } catch (error: Throwable) {
            result.putString("failure", error.toString())
            result.putString("stream", "FAIL: ${error.message}\n")
        } finally {
            manager.activeNotifications.filter { it.tag?.contains(marker) == true }.forEach { manager.cancel(it.tag, it.id) }
            runner.finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }
}
