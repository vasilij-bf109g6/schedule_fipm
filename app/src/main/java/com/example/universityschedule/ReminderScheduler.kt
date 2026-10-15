package com.example.universityschedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

// Держит в AlarmManager ровно один будильник — на ближайшее напоминание.
// После срабатывания ReminderReceiver показывает уведомление и ставит следующий.
object ReminderScheduler {

    const val CHANNEL_ID = "class_reminders"
    const val ACTION_REMINDER = "com.example.universityschedule.action.CLASS_REMINDER"
    const val EXTRA_SUBJECT = "subject"
    const val EXTRA_ROOM = "room"
    const val EXTRA_TEACHER = "teacher"
    const val EXTRA_START_TIME = "start_time"
    const val EXTRA_START_AT = "start_at"

    // Расписание своей группы загрузилось — запоминаем его, переставляем будильник и обновляем виджет
    fun onScheduleLoaded(context: Context, schedule: Map<Int, List<UniversityClass>>) {
        ScheduleCache(context).save(schedule)
        reschedule(context)
        ScheduleWidget.updateAll(context)
    }

    // Своя группа сменилась — старое расписание больше не актуально
    fun onGroupChanged(context: Context) {
        ScheduleCache(context).clear()
        reschedule(context)
        ScheduleWidget.updateAll(context)
    }

    fun reschedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val leadMinutes = UserSettings(context).reminderLeadMinutes
        val reminder = ScheduleLogic.nextReminder(ScheduleCache(context).load(), Calendar.getInstance(), leadMinutes)

        if (reminder == null) {
            alarmManager.cancel(pendingIntent(context, null))
            return
        }

        val pendingIntent = pendingIntent(context, reminder)
        try {
            if (canScheduleExact(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
            } else {
                // Без разрешения на точные будильники система может задержать напоминание на несколько минут
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Разрешение на точные будильники отозвали между проверкой и вызовом
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pendingIntent)
        }
        Log.d("Reminders", "Следующее напоминание: ${reminder.universityClass.subject} в ${reminder.triggerAtMillis}")
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.reminders_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    // Один и тот же requestCode — новый будильник заменяет старый
    private fun pendingIntent(context: Context, reminder: Reminder?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)
        reminder?.let {
            intent.putExtra(EXTRA_SUBJECT, it.universityClass.subject)
                .putExtra(EXTRA_ROOM, it.universityClass.room)
                .putExtra(EXTRA_TEACHER, it.universityClass.teacher)
                .putExtra(EXTRA_START_TIME, it.universityClass.startTime)
                .putExtra(EXTRA_START_AT, it.startAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
