package com.example.universityschedule

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// Срабатывает по будильнику: показывает уведомление о паре и ставит следующее
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val startAt = intent.getLongExtra(ReminderScheduler.EXTRA_START_AT, 0L)
        // Будильник сильно опоздал (телефон был выключен) — о начавшейся паре не напоминаем
        if (startAt > System.currentTimeMillis()) {
            showNotification(context, intent, startAt)
        }

        ReminderScheduler.reschedule(context)
    }

    private fun showNotification(context: Context, intent: Intent, startAt: Long) {
        // Разрешение POST_NOTIFICATIONS есть только с Android 13; раньше достаточно включённых уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) return

        val subject = intent.getStringExtra(ReminderScheduler.EXTRA_SUBJECT).orEmpty()
        val room = intent.getStringExtra(ReminderScheduler.EXTRA_ROOM).orEmpty()
        val teacher = intent.getStringExtra(ReminderScheduler.EXTRA_TEACHER).orEmpty()
        val startTime = intent.getStringExtra(ReminderScheduler.EXTRA_START_TIME).orEmpty()
        val minutesLeft = ((startAt - System.currentTimeMillis() + 59_999) / 60_000).toInt()

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val details = listOf(
            context.getString(R.string.class_room, room).takeIf { room.isNotBlank() },
            teacher.takeIf { it.isNotBlank() },
            startTime
        ).filterNotNull().joinToString(" · ")

        ReminderScheduler.createChannel(context)
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title, minutesLeft, subject))
            .setContentText(details)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и показом
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}

// Будильники сбрасываются после перезагрузки, смены времени и обновления приложения — ставим заново
// (и будильник напоминания, и будильник обновления виджета).
// Все действия здесь защищённые системные, а ресивер только пересчитывает будильник.
class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                ReminderScheduler.reschedule(context)
                ScheduleWidget.updateAll(context)
            }
        }
    }
}
