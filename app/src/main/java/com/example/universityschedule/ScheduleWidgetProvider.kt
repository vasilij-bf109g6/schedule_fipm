package com.example.universityschedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

// Виджет «Сейчас / Следующая пара». Данные — сохранённое расписание своей группы (ScheduleCache).
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ScheduleWidget.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ScheduleWidget.ACTION_REFRESH) ScheduleWidget.updateAll(context)
    }

    override fun onDisabled(context: Context) {
        // Последний виджет убрали с экрана — будильник обновления больше не нужен
        context.getSystemService(AlarmManager::class.java)?.cancel(ScheduleWidget.refreshIntent(context))
    }
}

object ScheduleWidget {

    const val ACTION_REFRESH = "com.example.universityschedule.action.WIDGET_REFRESH"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidgetProvider::class.java))
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (ids.isEmpty()) {
            alarmManager?.cancel(refreshIntent(context))
            return
        }

        val cache = ScheduleCache(context)
        val schedule = cache.load()
        val now = Calendar.getInstance()
        manager.updateAppWidget(ids, buildViews(context, schedule, cache.hasData(), now))

        // Перерисуемся в начале или конце ближайшей пары. Не будим телефон:
        // если экран выключен, виджет всё равно никто не видит, обновится при включении
        alarmManager?.set(AlarmManager.RTC, ScheduleLogic.nextWidgetUpdate(schedule, now), refreshIntent(context))
    }

    fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ScheduleWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildViews(
        context: Context,
        schedule: Map<Int, List<UniversityClass>>,
        hasData: Boolean,
        now: Calendar
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        views.setTextViewText(R.id.widget_group, UserSettings(context).myGroup?.shortName(context).orEmpty())
        views.setViewVisibility(R.id.widget_next, View.GONE)

        if (!hasData) {
            views.setTextViewText(R.id.widget_label, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_subject, context.getString(R.string.widget_no_data))
            views.setTextViewText(R.id.widget_details, "")
            return views
        }

        when (val state = ScheduleLogic.widgetState(schedule, now)) {
            is WidgetState.Current -> {
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_now))
                views.setTextViewText(R.id.widget_subject, state.current.subject)
                views.setTextViewText(
                    R.id.widget_details,
                    details(context, context.getString(R.string.widget_until, state.current.endTime), state.current)
                )
                state.nextToday?.let { next ->
                    views.setTextViewText(R.id.widget_next, context.getString(R.string.widget_next, next.subject, next.startTime))
                    views.setViewVisibility(R.id.widget_next, View.VISIBLE)
                }
            }
            is WidgetState.Upcoming -> {
                val next = state.next.universityClass
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_next_label))
                views.setTextViewText(R.id.widget_subject, next.subject)
                views.setTextViewText(R.id.widget_details, details(context, whenText(context, state.next), next))
            }
            WidgetState.NoClasses -> {
                views.setTextViewText(R.id.widget_label, context.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_subject, context.getString(R.string.status_no_classes))
                views.setTextViewText(R.id.widget_details, "")
            }
        }
        return views
    }

    // «Сегодня в 10:40», «Завтра в 9:00», «Пн в 9:00»
    private fun whenText(context: Context, reminder: Reminder): String {
        val time = reminder.universityClass.startTime
        return when (reminder.dayOffset) {
            0 -> context.getString(R.string.widget_today_at, time)
            1 -> context.getString(R.string.widget_tomorrow_at, time)
            else -> {
                val index = ScheduleLogic.WEEK_DAYS.indexOf(reminder.universityClass.dayOfWeek)
                val dayName = context.resources.getStringArray(R.array.day_short_names)
                    .getOrNull(index).orEmpty()
                    .replaceFirstChar { it.uppercase() }
                context.getString(R.string.widget_day_at, dayName, time)
            }
        }
    }

    private fun details(context: Context, timeText: String, universityClass: UniversityClass): String =
        listOfNotNull(
            timeText,
            context.getString(R.string.class_room, universityClass.room).takeIf { universityClass.room.isNotBlank() }
        ).joinToString(" · ")
}
