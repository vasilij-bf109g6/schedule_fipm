package com.example.universityschedule

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

// Одноразовое событие для LiveData: тост не должен показываться повторно после поворота экрана
class Event<out T>(private val content: T) {
    private var handled = false

    fun getContentIfNotHandled(): T? {
        if (handled) return null
        handled = true
        return content
    }
}

enum class LoadError { OFFLINE, NOT_FOUND, BAD_DATA, UNKNOWN }

// Успешно загруженное расписание вместе с группой — для кэша напоминаний и виджета
data class LoadedSchedule(val groupId: String, val schedule: Map<Int, List<UniversityClass>>)

sealed class LoadResult {
    object Success : LoadResult()
    data class Failure(val error: LoadError) : LoadResult()
}

class ScheduleViewModel(private val repository: ScheduleRepository) : ViewModel() {

    private val _weeklySchedule = MutableLiveData<Map<Int, List<UniversityClass>>>(emptyMap())
    val weeklySchedule: LiveData<Map<Int, List<UniversityClass>>> = _weeklySchedule

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // true — на экране данные из кэша: нет связи с сервером
    private val _isFromCache = MutableLiveData(false)
    val isFromCache: LiveData<Boolean> = _isFromCache

    // Success приходит только для ручного обновления, Failure — для любой загрузки
    private val _loadResult = MutableLiveData<Event<LoadResult>>()
    val loadResult: LiveData<Event<LoadResult>> = _loadResult

    // Публикуется только при настоящем изменении расписания
    private val _loadedSchedule = MutableLiveData<LoadedSchedule>()
    val loadedSchedule: LiveData<LoadedSchedule> = _loadedSchedule

    private var watchJob: Job? = null
    private var watchedKey: String? = null
    private var publishedKey: String? = null
    // Подписка присылает одно и то же состояние несколько раз — показываем ошибку один раз
    private var lastError: LoadError? = null

    // Подписаться на расписание группы. Повторный вызов для той же группы ничего не делает
    fun watchSchedule(groupId: String) {
        val key = groupId
        if (key == watchedKey && watchJob?.isActive == true) return

        watchJob?.cancel()
        if (key != watchedKey) {
            // Не оставляем на экране расписание другой группы
            _weeklySchedule.value = emptyMap()
            _isFromCache.value = false
            publishedKey = null
        }
        watchedKey = key
        lastError = null
        _isLoading.value = true

        watchJob = viewModelScope.launch {
            repository.observeSchedule(groupId)
                .catch { e ->
                    Log.e("ScheduleVM", "Подписка на расписание $key оборвалась", e)
                    _isLoading.value = false
                    reportError((e as? Exception)?.toLoadError() ?: LoadError.UNKNOWN)
                }
                .collect { update -> handleUpdate(update, groupId) }
        }
    }

    // Экран не виден — отписываемся, чтобы не держать соединение
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    // Ручное обновление: запрос прямо к серверу. Подписка при этом продолжает работать
    fun refresh(groupId: String) {
        val key = groupId
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val schedule = repository.fetchFromServer(groupId)
                if (key == watchedKey) {
                    _isFromCache.value = false
                    publish(schedule, groupId)
                }
                _loadResult.value = Event(LoadResult.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ScheduleVM", "Ошибка обновления расписания $key", e)
                _loadResult.value = Event(LoadResult.Failure(e.toLoadError()))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getScheduleForDay(dayOfWeek: Int): List<UniversityClass> {
        return _weeklySchedule.value?.get(dayOfWeek) ?: emptyList()
    }

    private fun handleUpdate(update: ScheduleUpdate, groupId: String) {
        _isLoading.value = false
        when (update) {
            is ScheduleUpdate.Loaded -> {
                lastError = null
                _isFromCache.value = update.fromCache
                publish(update.schedule, groupId)
            }
            is ScheduleUpdate.Missing -> {
                _isFromCache.value = update.fromCache
                _weeklySchedule.value = emptyMap()
                publishedKey = null
                // Документа нет в кэше и нет связи — это «нет сети», а не «нет расписания»
                reportError(if (update.fromCache) LoadError.OFFLINE else LoadError.NOT_FOUND)
            }
            // Оставляем на экране последнее корректное расписание этой группы
            ScheduleUpdate.Invalid -> reportError(LoadError.BAD_DATA)
        }
    }

    private fun publish(schedule: Map<Int, List<UniversityClass>>, groupId: String) {
        val key = groupId
        if (key == publishedKey && schedule == _weeklySchedule.value) return
        Log.d("ScheduleVM", "Расписание $key обновлено: ${schedule.size} дней")
        publishedKey = key
        _weeklySchedule.value = schedule
        _loadedSchedule.value = LoadedSchedule(groupId, schedule)
    }

    private fun reportError(error: LoadError) {
        if (error == lastError) return
        lastError = error
        _loadResult.value = Event(LoadResult.Failure(error))
    }

    private fun Exception.toLoadError(): LoadError = when {
        this is ScheduleNotFoundException -> LoadError.NOT_FOUND
        this is InvalidScheduleException -> LoadError.BAD_DATA
        this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE -> LoadError.OFFLINE
        else -> LoadError.UNKNOWN
    }
}
