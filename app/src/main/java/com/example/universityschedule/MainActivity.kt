package com.example.universityschedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settings: UserSettings
    private lateinit var viewModel: ScheduleViewModel
    private lateinit var schedulePager: ViewPager2
    private val pagerAdapter = DayPagerAdapter(
        scheduleForDay = { day -> viewModel.getScheduleForDay(day) },
        isTodayPage = { page -> page == ScheduleLogic.pageFor(0, currentDayOfWeek()) }
    )

    private lateinit var currentClassTextView: TextView
    private lateinit var scheduleTitleTextView: TextView
    private lateinit var daysContainer: LinearLayout
    private lateinit var toolbarSubtitleTextView: TextView
    private lateinit var toolbarDateTextView: TextView
    private lateinit var btnNextWeek: ImageView

    // Текущий выбранный день (по умолчанию сегодня)
    private var selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    private var weekOffset = 0 // 0 - текущая неделя, 1 - следующая

    // Группы для «Посмотреть другие»: встроенный список, пока не загрузился список из Firestore
    private var availableGroups: List<Group> = emptyList()

    // Варианты «за сколько минут напоминать»; порядок совпадает с R.array.reminder_options
    private val reminderLeadOptions = listOf(0, 5, 10, 15, 30)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onRemindersEnabled()
            } else {
                settings.reminderLeadMinutes = 0
                ReminderScheduler.reschedule(this)
                toast(getString(R.string.reminders_permission_denied), Toast.LENGTH_LONG)
            }
        }

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

        // Подписка на расписание включается в onStart и выключается в onStop
        viewModel = ViewModelProvider(this, ViewModelFactory(ScheduleRepository()))[ScheduleViewModel::class.java]

        setupPager()

        btnNextWeek.setOnClickListener {
            schedulePager.setCurrentItem(ScheduleLogic.pageFor(1 - weekOffset, selectedDay), true)
        }

        // Пустое расписание тоже показываем — иначе на экране остаются старые данные
        viewModel.weeklySchedule.observe(this) {
            pagerAdapter.refreshAll()
            updateSelectedDayInfo()
        }

        // Расписание своей группы (не временного просмотра) сохраняем для напоминаний
        viewModel.loadedSchedule.observe(this) { loaded ->
            if (loaded.groupId == settings.myGroup?.id) {
                ReminderScheduler.onScheduleLoaded(this, loaded.schedule)
            }
        }

        viewModel.isLoading.observe(this) { updateScheduleTitle() }
        viewModel.isFromCache.observe(this) { updateScheduleTitle() }

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
        loadGroups()
        // Раз в несколько часов обновляем сохранённое расписание в фоне (для напоминаний и виджета)
        ScheduleSyncWorker.schedule(this)
    }

    private fun loadGroups() {
        availableGroups = defaultGroups(this)
        lifecycleScope.launch {
            ScheduleRepository().loadGroups()?.let { loaded ->
                availableGroups = loaded
                // Группу переименовали в Firestore — показываем новое название
                settings.updateNames(loaded)
                updateToolbar()
                ScheduleWidget.updateAll(this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        invalidateOptionsMenu()
        updateToolbar()
        watchActiveGroup()
    }

    override fun onStop() {
        super.onStop()
        // В фоне подписка не нужна: напоминания и виджет работают по сохранённому расписанию
        viewModel.stopWatching()
    }

    private fun initViews() {
        currentClassTextView = findViewById(R.id.currentClassTextView)
        scheduleTitleTextView = findViewById(R.id.scheduleTitleTextView)
        daysContainer = findViewById(R.id.daysContainer)
        toolbarSubtitleTextView = findViewById(R.id.toolbar_subtitle)
        toolbarDateTextView = findViewById(R.id.toolbar_date)
        btnNextWeek = findViewById(R.id.btnNextWeek)
        schedulePager = findViewById(R.id.schedulePager)
    }

    // Свайп между днями: 14 страниц — текущая и следующая неделя. Выбранный день и неделя
    // всегда берутся из текущей страницы, кнопки дней и стрелка просто листают пейджер
    private fun setupPager() {
        schedulePager.adapter = pagerAdapter
        schedulePager.setCurrentItem(ScheduleLogic.pageFor(weekOffset, selectedDay), false)
        schedulePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedDay = ScheduleLogic.dayForPage(position)
                weekOffset = ScheduleLogic.weekOffsetForPage(position)
                btnNextWeek.setImageResource(
                    if (weekOffset == 0) android.R.drawable.ic_media_next else android.R.drawable.ic_media_previous
                )
                updateDaysPanel()
                updateSelectedDayInfo()
            }
        })
    }

    // Изменения в Firestore приходят сразу, пока экран открыт
    private fun watchActiveGroup() {
        val group = settings.activeGroup ?: return
        viewModel.watchSchedule(group.id)
    }

    // «Загрузка…», «Расписание (оффлайн)» — если данные из кэша, или обычный заголовок
    private fun updateScheduleTitle() {
        scheduleTitleTextView.setText(
            when {
                viewModel.isLoading.value == true -> R.string.schedule_title_loading
                viewModel.isFromCache.value == true -> R.string.schedule_title_offline
                else -> R.string.schedule_title
            }
        )
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
                        pagerAdapter.refreshAll()
                        updateDaysPanel()
                        updateSelectedDayInfo()
                    } else {
                        if (isSelectedDayToday()) updateCurrentClassStatus()
                        pagerAdapter.refreshTiming()
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
                    schedulePager.setCurrentItem(ScheduleLogic.pageFor(weekOffset, day), true)
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

    // Заголовок, статус и дата для выбранного дня; сами списки пар живут на страницах пейджера
    private fun updateSelectedDayInfo() {
        val schedule = viewModel.getScheduleForDay(selectedDay)

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
        toolbarSubtitleTextView.text = settings.activeGroup?.shortName.orEmpty()
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
            R.id.menu_reminders -> showRemindersDialog()
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
            return
        }

        val isTemporary = settings.temporaryGroup != null
        toast(getString(if (isTemporary) R.string.refresh_started_temporary else R.string.refresh_started_mine))
        // Результат придёт в наблюдатель viewModel.loadResult
        val group = settings.activeGroup ?: return
        viewModel.refresh(group.id)
    }

    private fun getReadableError(error: LoadError): String = getString(
        when (error) {
            LoadError.OFFLINE -> R.string.error_offline
            LoadError.NOT_FOUND -> R.string.error_not_found
            LoadError.BAD_DATA -> R.string.error_bad_data
            LoadError.UNKNOWN -> R.string.error_unknown
        }
    )

    // ---------- Напоминания ----------

    private fun showRemindersDialog() {
        val current = reminderLeadOptions.indexOf(settings.reminderLeadMinutes).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_reminders)
            .setSingleChoiceItems(R.array.reminder_options, current) { dialog, which ->
                dialog.dismiss()
                setReminderLead(reminderLeadOptions[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setReminderLead(minutes: Int) {
        settings.reminderLeadMinutes = minutes

        if (minutes == 0) {
            ReminderScheduler.reschedule(this)
            toast(getString(R.string.reminders_disabled))
            return
        }

        // С Android 13 уведомления нужно разрешить явно; ответ придёт в notificationPermissionLauncher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        onRemindersEnabled()
    }

    private fun onRemindersEnabled() {
        ReminderScheduler.createChannel(this)
        ReminderScheduler.reschedule(this)
        toast(getString(R.string.reminders_enabled, settings.reminderLeadMinutes))

        // С Android 12 точные будильники требуют отдельного разрешения; без него напоминания могут опаздывать
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ReminderScheduler.canScheduleExact(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reminders_exact_title)
                .setMessage(R.string.reminders_exact_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.not_now, null)
                .show()
        }
    }

    private fun showChangeGroupDialog() {
        val myGroup = settings.myGroup ?: return

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_change_group)
            .setMessage(getString(R.string.dialog_change_group_message, myGroup.name))
            .setPositiveButton(R.string.menu_change_group) { _, _ ->
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOtherSchedulesDialog() {
        val groups = availableGroups
        val titles = groups.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_other_groups_title)
            .setItems(titles) { _, which ->
                val group = groups[which]

                if (group.isSameAs(settings.myGroup)) {
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
                watchActiveGroup()
                toast(getString(R.string.toast_group_loaded, group.name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun returnToMyGroup() {
        if (settings.temporaryGroup == null) return

        settings.temporaryGroup = null
        invalidateOptionsMenu()
        updateToolbar()
        watchActiveGroup()
        toast(getString(R.string.toast_returned_to_my_group))
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }
}
