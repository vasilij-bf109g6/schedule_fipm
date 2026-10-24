package com.example.universityschedule

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.TimeUnit

// Фоновое обновление сохранённого расписания своей группы, когда приложение закрыто.
// Нужно напоминаниям и виджету: иначе они узнают об отменах, только когда приложение откроют.
class ScheduleSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val group = UserSettings(context).myGroup ?: return Result.success()

        // Напоминания выключены и виджета нет — свежее расписание в фоне никому не нужно
        if (UserSettings(context).reminderLeadMinutes <= 0 && !ScheduleWidget.hasWidgets(context)) {
            return Result.success()
        }

        return try {
            val schedule = ScheduleRepository().fetchFromServer(group.id)
            ReminderScheduler.onScheduleLoaded(context, schedule)
            Log.d("ScheduleSync", "Фоновое обновление расписания ${group.id}: ${schedule.size} дней")
            Result.success()
        } catch (e: FirebaseFirestoreException) {
            // Сеть пропала посреди запроса — WorkManager повторит позже
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) Result.retry() else Result.failure()
        } catch (e: Exception) {
            // Документа нет или он заполнен с ошибкой — повтор не поможет, ждём следующего запуска
            Log.w("ScheduleSync", "Фоновое обновление расписания ${group.id} не удалось", e)
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "schedule_sync"
        private const val INTERVAL_HOURS = 3L

        // Повторный вызов ничего не меняет (KEEP): задача уже стоит в очереди и переживает перезагрузку
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
