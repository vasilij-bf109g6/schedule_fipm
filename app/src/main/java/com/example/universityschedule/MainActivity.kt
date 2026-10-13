package com.example.universityschedule

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settings: UserSettings
    private lateinit var viewModel: ScheduleViewModel
    private val scheduleAdapter = ScheduleAdapter()

    private lateinit var currentClassTextView: TextView
    private lateinit var scheduleTitleTextView: TextView
    private lateinit var daysContainer: LinearLayout
    private lateinit var toolbarSubtitleTextView: TextView
    private lateinit var toolbarDateTextView: TextView
    private lateinit var btnNextWeek: ImageView

    // Текущий выбранный день (по умолчанию сегодня)
    private var selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    private var weekOffset = 0 // 0 - текущая неделя, 1 - следующая

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = UserSettings(this)

        // Группа не выбрана - открываем экран выбора группы
        if (settings.myGroup == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        initViews()
        setupDaysPanel()

        // Само расписание загружается в onResume
        viewModel = ViewModelProvider(this, ViewModelFactory(ScheduleRepository()))[ScheduleViewModel::class.java]

        btnNextWeek.setOnClickListener {
            weekOffset = if (weekOffset == 0) 1 else 0
            btnNextWeek.setImageResource(
                if (weekOffset == 0) android.R.drawable.ic_media_next else android.R.drawable.ic_media_previous
            )
            updateDaysPanel()
            updateScheduleForSelectedDay()
        }

        // Пустое расписание тоже показываем — иначе на экране остаются старые данные
        viewModel.weeklySchedule.observe(this) {
            updateScheduleForSelectedDay()
        }

        viewModel.isLoading.observe(this) { isLoading ->
            scheduleTitleTextView.setText(if (isLoading) R.string.schedule_title_loading else R.string.schedule_title)
        }

        // Ошибки показываем всегда, успех — только после ручного обновления
        viewModel.loadResult.observe(this) { event ->
            when (val result = event.getContentIfNotHandled()) {
                is LoadResult.Failure -> toast(getString(R.string.load_failed, getReadableError(result.error)), Toast.LENGTH_LONG)
                LoadResult.Success -> toast(
                    getString(if (settings.temporaryGroup != null) R.string.refresh_done_temporary else R.string.refresh_done_mine)
                )
                null -> Unit
            }
        }

        startMinuteTicker()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        updateToolbar()
        loadActiveGroup()
    }

    private fun initViews() {
        currentClassTextView = findViewById(R.id.currentClassTextView)
        scheduleTitleTextView = findViewById(R.id.scheduleTitleTextView)
        daysContainer = findViewById(R.id.daysContainer)
        toolbarSubtitleTextView = findViewById(R.id.toolbar_subtitle)
        toolbarDateTextView = findViewById(R.id.toolbar_date)
        btnNextWeek = findViewById(R.id.btnNextWeek)
        findViewById<RecyclerView>(R.id.scheduleRecyclerView).adapter = scheduleAdapter
    }

    private fun loadActiveGroup(userInitiated: Boolean = false) {
        val group = settings.activeGroup ?: return
        viewModel.loadSchedule(group.subgroup, group.interest.key, userInitiated)
    }

    // Раз в минуту обновляем статус и подсветку; работает, пока экран виден
    private fun startMinuteTicker() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastDay = currentDayOfWeek()
                while (true) {
                    val now = Calendar.getInstance()
                    delay((60 - now.get(Calendar.SECOND)) * 1000L)

                    // Наступила полночь — «сегодня» сместилось
                    if (currentDayOfWeek() != lastDay) {
                        lastDay = currentDayOfWeek()
                        updateDaysPanel()
                        updateScheduleForSelectedDay()
                    } else if (isSelectedDayToday()) {
                        updateCurrentClassStatus()
                        scheduleAdapter.refreshTiming()
                    }
                }
            }
        }
    }

    // ---------- Даты ----------

    private fun currentDayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    // Тот же день недели на следующей неделе — уже не «сегодня»
    private fun isSelectedDayToday(): Boolean = weekOffset == 0 && selectedDay == currentDayOfWeek()

    // «13 Октября» для выбранного дня с учётом weekOffset
    private fun formatDateForDay(targetDay: Int): String {
        val calendar = Calendar.getInstance()
        // Считаем от понедельника вручную: Calendar.set(DAY_OF_WEEK) зависит от локали
        val daysToAdd = ScheduleLogic.daysFromMonday(targetDay) - ScheduleLogic.daysFromMonday(currentDayOfWeek()) + 7 * weekOffset
        calendar.add(Calendar.DAY_OF_MONTH, daysToAdd)

        val russian = Locale("ru")
        val formatted = SimpleDateFormat("d MMMM", russian).format(calendar.time)
        val spaceIndex = formatted.indexOf(' ')
        if (spaceIndex == -1) return formatted
        return formatted.substring(0, spaceIndex + 1) +
            formatted.substring(spaceIndex + 1).replaceFirstChar { it.titlecase(russian) }
    }

    // ---------- Панель дней ----------

    private fun setupDaysPanel() {
        val dayNames = resources.getStringArray(R.array.day_short_names)
        daysContainer.removeAllViews()

        ScheduleLogic.WEEK_DAYS.forEachIndexed { index, day ->
            val dayButton = AppCompatButton(this).apply {
                text = dayNames[index]
                isAllCaps = false
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                background = ContextCompat.getDrawable(context, R.drawable.circle_background)
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    marginEnd = dpToPx(4)
                }
                setOnClickListener {
                    selectedDay = day
                    updateDaysPanel()
                    updateScheduleForSelectedDay()
                }
            }
            daysContainer.addView(dayButton)
        }

        updateDaysPanel()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun updateDaysPanel() {
        val today = currentDayOfWeek()

        ScheduleLogic.WEEK_DAYS.forEachIndexed { index, day ->
            val button = daysContainer.getChildAt(index) as? AppCompatButton ?: return@forEachIndexed
            val isToday = weekOffset == 0 && day == today

            val (backgroundRes, textRes, alpha) = when {
                day == selectedDay -> Triple(R.color.daySelected, android.R.color.white, 1.0f)
                isToday -> Triple(R.color.dayToday, android.R.color.white, 1.0f)
                // Дни следующей недели полупрозрачные
                weekOffset != 0 -> Triple(R.color.surface, R.color.dayNormal, 0.6f)
                else -> Triple(R.color.surface, R.color.dayNormal, 1.0f)
            }

            // Красим через tint, чтобы кнопка осталась круглой
            ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(ContextCompat.getColor(this, backgroundRes)))
            button.setTextColor(ContextCompat.getColor(this, textRes))
            button.alpha = alpha
        }
    }

    // ---------- Расписание и статус ----------

    private fun updateScheduleForSelectedDay() {
        val schedule = viewModel.getScheduleForDay(selectedDay)
        scheduleAdapter.submit(schedule, isSelectedDayToday())

        // Воскресенье всегда скрываем, другие дни - только если нет пар
        val isSunday = selectedDay == Calendar.SUNDAY
        scheduleTitleTextView.visibility = if (isSunday || schedule.isEmpty()) View.GONE else View.VISIBLE

        updateCurrentClassStatus()
        updateToolbarDate()
    }

    private fun updateCurrentClassStatus() {
        val schedule = viewModel.getScheduleForDay(selectedDay)
        val activeSchedule = ScheduleLogic.activeSorted(schedule)

        // Воскресенье — единственный выходной
        if (selectedDay == Calendar.SUNDAY) {
            showStatus(getString(R.string.status_no_classes), R.color.colorPastClass)
            return
        }

        if (weekOffset > 0) {
            if (activeSchedule.isEmpty()) {
                showStatus(getString(R.string.status_no_classes_next_week), R.color.colorPastClass)
            } else {
                showStatus(getString(R.string.status_next_week_starts, activeSchedule.first().startTime), R.color.colorFutureClass)
            }
            return
        }

        // Другой день текущей недели: статус показываем, только если пар нет
        if (!isSelectedDayToday()) {
            if (activeSchedule.isEmpty()) {
                showStatus(getString(R.string.status_no_classes), R.color.colorPastClass)
            } else {
                currentClassTextView.visibility = View.GONE
            }
            return
        }

        val now = ScheduleLogic.minutesOfDay(Calendar.getInstance())
        when (val status = ScheduleLogic.todayStatus(schedule, now)) {
            DayStatus.NoClasses -> showStatus(getString(R.string.status_no_classes), R.color.colorPastClass)
            is DayStatus.BeforeFirst -> showStatus(
                getString(R.string.status_before_first, status.first.startTime),
                R.color.colorPastClass
            )
            is DayStatus.InProgress -> showStatus(
                getString(R.string.status_in_progress, status.current.subject, formatTimeLeft(status.minutesLeft)),
                R.color.colorCurrentClass
            )
            is DayStatus.Break -> showStatus(
                getString(R.string.status_break, status.next.subject, status.next.startTime),
                R.color.colorPastClass
            )
            DayStatus.Finished -> showStatus(getString(R.string.status_finished), R.color.colorPastClass)
        }
    }

    private fun showStatus(text: String, @ColorRes colorRes: Int) {
        currentClassTextView.text = text
        ViewCompat.setBackgroundTintList(currentClassTextView, ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)))
        currentClassTextView.visibility = View.VISIBLE
    }

    private fun formatTimeLeft(totalMinutes: Int): String {
        if (totalMinutes <= 0) return getString(R.string.time_left_ending)
        if (totalMinutes < 60) return getString(R.string.time_left, getString(R.string.minutes_short, totalMinutes))

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        var text = resources.getQuantityString(R.plurals.hours, hours, hours)
        if (minutes > 0) text += " " + resources.getQuantityString(R.plurals.minutes, minutes, minutes)
        return getString(R.string.time_left, text)
    }

    // ---------- Toolbar ----------

    private fun updateToolbar() {
        toolbarSubtitleTextView.text = settings.activeGroup?.shortName(this).orEmpty()
        updateToolbarDate()
    }

    private fun updateToolbarDate() {
        toolbarDateTextView.text = getString(R.string.toolbar_date, formatDateForDay(selectedDay))
    }

    // ---------- Меню ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // Пункт "Моя группа" виден только во временном просмотре
        menu.findItem(R.id.menu_return_to_my)?.isVisible = settings.temporaryGroup != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> refreshSchedule()
            R.id.menu_change_group -> showChangeGroupDialog()
            R.id.menu_view_other -> showOtherSchedulesDialog()
            R.id.menu_return_to_my -> returnToMyGroup()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refreshSchedule() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            toast(getString(R.string.refresh_no_network), Toast.LENGTH_LONG)
            scheduleTitleTextView.setText(R.string.schedule_title_offline)
            return
        }

        val isTemporary = settings.temporaryGroup != null
        toast(getString(if (isTemporary) R.string.refresh_started_temporary else R.string.refresh_started_mine))
        // Результат придёт в наблюдатель viewModel.loadResult
        loadActiveGroup(userInitiated = true)
    }

    private fun getReadableError(error: LoadError): String = getString(
        when (error) {
            LoadError.OFFLINE -> R.string.error_offline
            LoadError.NOT_FOUND -> R.string.error_not_found
            LoadError.BAD_DATA -> R.string.error_bad_data
            LoadError.UNKNOWN -> R.string.error_unknown
        }
    )

    private fun showChangeGroupDialog() {
        val myGroup = settings.myGroup ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_change_group)
            .setMessage(getString(R.string.dialog_change_group_message, myGroup.fullName(this)))
            .setPositiveButton(R.string.menu_change_group) { _, _ ->
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOtherSchedulesDialog() {
        val groups = Group.ALL
        val titles = groups.map {
            getString(R.string.group_list_item, it.subgroup, getString(it.interest.titleRes))
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_other_groups_title)
            .setItems(titles) { _, which ->
                val group = groups[which]

                if (group == settings.myGroup) {
                    // Из временного просмотра выбор своей группы возвращает к ней
                    if (settings.temporaryGroup != null) {
                        returnToMyGroup()
                    } else {
                        toast(getString(R.string.toast_own_group))
                    }
                    return@setItems
                }

                settings.temporaryGroup = group
                invalidateOptionsMenu()
                updateToolbar()
                loadActiveGroup()
                toast(getString(R.string.toast_group_loaded, group.fullName(this)))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun returnToMyGroup() {
        if (settings.temporaryGroup == null) return

        settings.temporaryGroup = null
        invalidateOptionsMenu()
        updateToolbar()
        loadActiveGroup()
        toast(getString(R.string.toast_returned_to_my_group))
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }
}
