package com.example.universityschedule

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

sealed class LoadResult {
    object Success : LoadResult()
    data class Failure(val error: LoadError) : LoadResult()
}

class ScheduleViewModel(private val repository: ScheduleRepository) : ViewModel() {

    private val _weeklySchedule = MutableLiveData<Map<Int, List<UniversityClass>>>(emptyMap())
    val weeklySchedule: LiveData<Map<Int, List<UniversityClass>>> = _weeklySchedule

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Success приходит только для ручного обновления, Failure — для любой загрузки
    private val _loadResult = MutableLiveData<Event<LoadResult>>()
    val loadResult: LiveData<Event<LoadResult>> = _loadResult

    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var loadedKey: String? = null

    fun loadSchedule(subgroup: Int, interest: String, userInitiated: Boolean = false) {
        val key = "${subgroup}_$interest"
        val generation = ++loadGeneration
        loadJob?.cancel()
        _isLoading.value = true

        loadJob = viewModelScope.launch {
            try {
                val schedule = repository.getSchedule(subgroup, interest)
                if (generation != loadGeneration) return@launch
                loadedKey = key
                _weeklySchedule.value = schedule
                if (userInitiated) _loadResult.value = Event(LoadResult.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                Log.e("ScheduleVM", "Ошибка загрузки расписания $key", e)
                // Не оставляем на экране расписание другой группы
                if (loadedKey != key) {
                    loadedKey = null
                    _weeklySchedule.value = emptyMap()
                }
                _loadResult.value = Event(LoadResult.Failure(e.toLoadError()))
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }
        }
    }

    fun getScheduleForDay(dayOfWeek: Int): List<UniversityClass> {
        return _weeklySchedule.value?.get(dayOfWeek) ?: emptyList()
    }

    private fun Exception.toLoadError(): LoadError = when {
        this is ScheduleNotFoundException -> LoadError.NOT_FOUND
        this is InvalidScheduleException -> LoadError.BAD_DATA
        this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE -> LoadError.OFFLINE
        else -> LoadError.UNKNOWN
    }
}
