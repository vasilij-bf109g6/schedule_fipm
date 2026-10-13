package com.example.universityschedule

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.graphics.Color  // Добавьте этот импорт вверху файла
import android.graphics.Paint  // Добавьте этот импорт
import android.widget.ListView
import android.content.Context
import android.view.LayoutInflater
import java.util.Locale
import android.util.Log  // ←
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.content.Intent
import android.content.SharedPreferences
import java.util.Date
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import android.widget.TextView
import java.text.SimpleDateFormat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.TimerTask
import java.util.*
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Observer


class MainActivity : AppCompatActivity() {

    private lateinit var currentClassTextView: TextView
    private lateinit var scheduleTitleTextView: TextView
    private lateinit var scheduleListView: ListView
    private var updateTimer: Timer? = null
    private lateinit var daysContainer: LinearLayout
    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: Toolbar
    private lateinit var viewModel: ScheduleViewModel  // Добавляем ViewModel
    private lateinit var toolbarDateTextView: TextView
    private lateinit var toolbarTitleTextView: TextView
    private lateinit var toolbarSubtitleTextView: TextView
    private var weekOffset = 0 // 0 - текущая неделя, 1 - следующая, -1 - предыдущая
    private lateinit var btnNextWeek: ImageView



    // Текущий выбранный день (по умолчанию сегодня)
    private var selectedDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    // Для временного просмотра других групп
    private var isTemporaryView = false
    private var tempSubgroup = 1
    private var tempInterest = "readers"

    // Сохраняем оригинальные настройки
    private var originalSubgroup = 1
    private var originalInterest = "readers"
    private val dayNames = mapOf(
        Calendar.MONDAY to "пн",
        Calendar.TUESDAY to "вт",
        Calendar.WEDNESDAY to "ср",
        Calendar.THURSDAY to "чт",
        Calendar.FRIDAY to "пт",
        Calendar.SATURDAY to "сб",
        Calendar.SUNDAY to "вс"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Инициализируем SharedPreferences
        prefs = getSharedPreferences("user_settings", MODE_PRIVATE)
        // Восстанавливаем временный просмотр из SharedPreferences
        isTemporaryView = prefs.getBoolean("is_temporary_view", false)
        if (isTemporaryView) {
            tempSubgroup = prefs.getInt("temp_subgroup", 1)
            tempInterest = prefs.getString("temp_interest", "readers") ?: "readers"
            originalSubgroup = prefs.getInt("original_subgroup", 1)
            originalInterest = prefs.getString("original_interest", "readers") ?: "readers"
        }
        // Проверяем, есть ли сохранённые настройки
        if (!prefs.contains("subgroup") || !prefs.contains("interest_group")) {
            // Настроек нет - открываем экран выбора группы
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        // Настройки есть - инициализируем основной интерфейс
        setContentView(R.layout.activity_main)

        // Инициализируем Toolbar
        setupToolbar()

        // Инициализируем View элементы
        initViews()  // ← Этот метод теперь инициализирует toolbarSubtitleTextView
        setupDaysPanel()

        // === ИНИЦИАЛИЗАЦИЯ ViewModel ===
        // Само расписание загружается в onResume
        val repository = ScheduleRepository()
        val viewModelFactory = ViewModelFactory(repository)
        viewModel = ViewModelProvider(this, viewModelFactory).get(ScheduleViewModel::class.java)

        // Настройка переключения недель
        btnNextWeek.setOnClickListener {
            if (weekOffset == 0) {
                // Переходим на следующую неделю
                weekOffset = 1
                btnNextWeek.setImageResource(android.R.drawable.ic_media_previous)
                btnNextWeek.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            } else {
                // Возвращаемся к текущей неделе
                weekOffset = 0
                btnNextWeek.setImageResource(android.R.drawable.ic_media_next)
                btnNextWeek.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            }

            updateDaysPanel()
            updateScheduleForSelectedDay()
            updateCurrentClassStatus()
        }
        // Наблюдаем за расписанием
        // Пустое расписание тоже показываем — иначе на экране остаются старые данные
        viewModel.weeklySchedule.observe(this, Observer {
            updateScheduleForSelectedDay()
            updateToolbarTitle()
        })

        // Наблюдаем за состоянием загрузки
        viewModel.isLoading.observe(this, Observer { isLoading ->
            if (isLoading) {
                // Можно показать ProgressBar
                scheduleTitleTextView.text = "Загрузка расписания..."
            } else {
                scheduleTitleTextView.text = "📖 Расписание:"
            }
        })

        // Результат загрузки: ошибки показываем всегда, успех — только после ручного обновления
        viewModel.loadResult.observe(this, Observer { event ->
            when (val result = event.getContentIfNotHandled()) {
                is LoadResult.Failure -> Toast.makeText(
                    this,
                    "❌ Ошибка загрузки\n${getReadableError(result.error)}",
                    Toast.LENGTH_LONG
                ).show()
                LoadResult.Success -> {
                    val message = if (isTemporaryView) {
                        "✅ Временный просмотр обновлен"
                    } else {
                        "✅ Ваше расписание обновлено"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                null -> Unit
            }
        })
    }
    private fun formatDateWithCapitalMonth(date: Date): String {
        val dateFormat = SimpleDateFormat("d MMMM", Locale("ru"))
        val formattedDate = dateFormat.format(date)

        Log.d("DATE_FORMAT", "Исходная дата: $formattedDate")

        // Находим пробел и делаем следующую букву заглавной
        val spaceIndex = formattedDate.indexOf(' ')
        return if (spaceIndex != -1 && spaceIndex + 1 < formattedDate.length) {
            val dayPart = formattedDate.substring(0, spaceIndex + 1)
            val monthPart = formattedDate.substring(spaceIndex + 1)

            Log.d("DATE_FORMAT", "День: '$dayPart', Месяц: '$monthPart'")

            val capitalizedMonth = monthPart.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString()
            }

            val result = dayPart + capitalizedMonth
            Log.d("DATE_FORMAT", "Результат: $result")
            result
        } else {
            Log.d("DATE_FORMAT", "Не удалось разобрать дату: $formattedDate")
            formattedDate
        }
    }

    // Обновите остальные методы:
    private fun getCurrentDateFormatted(): String {
        return formatDateWithCapitalMonth(Date())
    }

    private fun getDateForDay(targetDay: Int): String {
        val calendar = Calendar.getInstance()

        // Считаем от понедельника вручную: Calendar.set(DAY_OF_WEEK) зависит от локали
        // (в США неделя начинается с воскресенья, и в воскресенье получался следующий понедельник)
        val today = calendar.get(Calendar.DAY_OF_WEEK)
        val daysToAdd = ScheduleLogic.daysFromMonday(targetDay) - ScheduleLogic.daysFromMonday(today) + 7 * weekOffset
        calendar.add(Calendar.DAY_OF_MONTH, daysToAdd)

        return formatDateWithCapitalMonth(calendar.time)
    }

    // Добавьте вспомогательный метод:
    private fun getDayName(day: Int): String {
        return when (day) {
            Calendar.SUNDAY -> "ВС"
            Calendar.MONDAY -> "ПН"
            Calendar.TUESDAY -> "ВТ"
            Calendar.WEDNESDAY -> "СР"
            Calendar.THURSDAY -> "ЧТ"
            Calendar.FRIDAY -> "ПТ"
            Calendar.SATURDAY -> "СБ"
            else -> "??"
        }
    }
    private fun getCurrentDayOfWeek(): Int {
        return Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    }

    // Тот же день недели на следующей неделе — уже не «сегодня»
    private fun isSelectedDayToday(): Boolean {
        return weekOffset == 0 && selectedDay == getCurrentDayOfWeek()
    }
    private fun refreshUI() {
        Log.d("REFRESH_UI", "=== НАЧАЛО ОБНОВЛЕНИЯ UI ===")

        // Всегда перечитываем SharedPreferences
        prefs = getSharedPreferences("user_settings", MODE_PRIVATE)

        val subgroup = prefs.getInt("subgroup", 1)
        val interest = prefs.getString("interest_group", "readers") ?: "readers"

        Log.d("REFRESH_UI", "Загружены настройки: subgroup=$subgroup, interest=$interest")

        // 1. Обновляем Toolbar (группа + дата)
        updateToolbarTitle()

        // ... остальной код
    }
    // Инициализация панели с днями недели (воскресенье в конце)
    private fun setupDaysPanel() {
        // Очищаем контейнер
        daysContainer.removeAllViews()

        // Порядок дней: ПН, ВТ, СР, ЧТ, ПТ, СБ, ВС
        val daysOrder = listOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY
        )

        // Создаем кнопки для каждого дня
        daysOrder.forEach { day ->
            createDayButton(day)
        }

        // Обновляем внешний вид
        updateDaysPanel()
    }

    private fun createDayButton(dayNumber: Int) {
        val dayButton = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = dayNames[dayNumber] ?: "?"
            isAllCaps = false

            // Увеличиваем размеры с 40dp до 48dp
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(48),  // Увеличили с 40 до 48
                dpToPx(48)   // Увеличили с 40 до 48
            ).apply {
                marginEnd = dpToPx(4)  // Оставляем 4dp
            }

            // Устанавливаем нормальный фон
            background = ContextCompat.getDrawable(context, R.drawable.circle_background)
            setTextColor(ContextCompat.getColor(context, R.color.dayNormal))
            textSize = 12f

            // Центрируем текст
            gravity = android.view.Gravity.CENTER

            // Убираем внутренние отступы у кнопки
            setPadding(0, 0, 0, 0)

            setOnClickListener {
                selectedDay = dayNumber
                updateDaysPanel()
                updateScheduleForSelectedDay()
            }
        }

        daysContainer.addView(dayButton)
    }

    // Вспомогательная функция для преобразования dp в пиксели
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateDaysPanel() {
        val today = getCurrentDayOfWeek()

        for (i in 0 until daysContainer.childCount) {
            val button = daysContainer.getChildAt(i) as androidx.appcompat.widget.AppCompatButton

            val dayNumber = when (i) {
                0 -> Calendar.MONDAY
                1 -> Calendar.TUESDAY
                2 -> Calendar.WEDNESDAY
                3 -> Calendar.THURSDAY
                4 -> Calendar.FRIDAY
                5 -> Calendar.SATURDAY
                6 -> Calendar.SUNDAY
                else -> continue
            }

            // Обновляем круглую форму
            button.background = ContextCompat.getDrawable(this, R.drawable.circle_background)

            // Определяем, является ли день сегодняшним (только на текущей неделе)
            val isToday = weekOffset == 0 && dayNumber == today

            when {
                isToday && dayNumber == selectedDay -> {
                    // Сегодня и выбран
                    button.setBackgroundColor(ContextCompat.getColor(this, R.color.daySelected))
                    button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    button.alpha = 1.0f
                }
                isToday -> {
                    // Сегодня, но не выбран
                    button.setBackgroundColor(ContextCompat.getColor(this, R.color.dayToday))
                    button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    button.alpha = 1.0f
                }
                dayNumber == selectedDay -> {
                    // Выбран, но не сегодня
                    button.setBackgroundColor(ContextCompat.getColor(this, R.color.daySelected))
                    button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    button.alpha = 1.0f
                }
                weekOffset != 0 -> {
                    // День следующей недели
                    button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                    button.setTextColor(ContextCompat.getColor(this, R.color.dayNormal))
                    button.alpha = 0.6f  // Полупрозрачность для дней следующей недели
                }
                else -> {
                    // Обычный день текущей недели
                    button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
                    button.setTextColor(ContextCompat.getColor(this, R.color.dayNormal))
                    button.alpha = 1.0f
                }
            }

            button.text = dayNames[dayNumber] ?: "?"
        }
    }

    // Обновление расписания для выбранного дня
    private fun updateScheduleForSelectedDay() {
        val schedule = viewModel.getScheduleForDay(selectedDay)

        scheduleListView.adapter = ScheduleAdapter(this, schedule, isSelectedDayToday())

        // Воскресенье всегда скрываем, другие дни - только если нет пар
        val isSunday = selectedDay == Calendar.SUNDAY
        scheduleTitleTextView.visibility = if (isSunday || schedule.isEmpty()) View.GONE else View.VISIBLE

        // Обновляем статус (перерыв, пар нет и т.д.)
        updateCurrentClassStatus()
        updateToolbarDateForSelectedDay()
    }

    private fun updateToolbarDateForSelectedDay() {
        // getDateForDay учитывает weekOffset, поэтому на следующей неделе дата тоже верная
        toolbarDateTextView.text = " | ${getDateForDay(selectedDay)}"
    }

    private fun setupToolbar() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

    // Новый метод для обновления заголовка
    private fun updateToolbarTitle() {
        Log.d("TOOLBAR_TITLE", "Обновление Toolbar...")

        prefs = getSharedPreferences("user_settings", MODE_PRIVATE)

        val subtitle = if (isTemporaryView) {
            val interestText = when (tempInterest) {
                "readers" -> "Чтецы"
                "singers" -> "Певцы"
                else -> tempInterest
            }
            "Гр.$tempSubgroup $interestText"
        } else {
            val subgroup = prefs.getInt("subgroup", 1)
            val interest = prefs.getString("interest_group", "readers") ?: "readers"
            val interestText = when (interest) {
                "readers" -> "Чтецы"
                "singers" -> "Певцы"
                else -> interest
            }
            "Гр.$subgroup $interestText"
        }
        toolbarSubtitleTextView.text = subtitle

        // Добавьте обновление даты!
        updateToolbarDateForSelectedDay()
    }

    private fun initViews() {
        currentClassTextView = findViewById(R.id.currentClassTextView)
        scheduleTitleTextView = findViewById(R.id.scheduleTitleTextView)
        scheduleListView = findViewById(R.id.scheduleListView)
        daysContainer = findViewById(R.id.daysContainer)
        toolbarTitleTextView = findViewById(R.id.toolbar_title)
        toolbarSubtitleTextView = findViewById(R.id.toolbar_subtitle)
        toolbarDateTextView = findViewById(R.id.toolbar_date)

        // Инициализируем ImageView стрелочки
        btnNextWeek = findViewById(R.id.btnNextWeek)
    }

    // Функция для обновления статуса текущей пары
    private fun updateCurrentClassStatus() {
        val schedule = viewModel.getScheduleForDay(selectedDay)
        val activeSchedule = ScheduleLogic.activeSorted(schedule)

        // Воскресенье — единственный выходной
        if (selectedDay == Calendar.SUNDAY) {
            showStatus("Пар нет", R.color.colorPastClass)
            return
        }

        // Следующая неделя
        if (weekOffset > 0) {
            if (activeSchedule.isEmpty()) {
                showStatus("Пар нет (следующая неделя)", R.color.colorPastClass)
            } else {
                showStatus("Следующая неделя: пары начнутся в ${activeSchedule.first().startTime}", R.color.colorFutureClass)
            }
            return
        }

        // Другой день текущей недели: статус показываем, только если пар нет
        if (!isSelectedDayToday()) {
            if (activeSchedule.isEmpty()) {
                showStatus("Пар нет", R.color.colorPastClass)
            } else {
                currentClassTextView.visibility = View.GONE
            }
            return
        }

        val now = ScheduleLogic.minutesOfDay(Calendar.getInstance())
        when (val status = ScheduleLogic.todayStatus(schedule, now)) {
            DayStatus.NoClasses -> showStatus("Пар нет", R.color.colorPastClass)
            is DayStatus.BeforeFirst -> showStatus("Пары начинаются в ${status.first.startTime}", R.color.colorPastClass)
            is DayStatus.InProgress -> showStatus(
                "Сейчас: ${status.current.subject} (${formatTimeLeft(status.minutesLeft)})",
                R.color.colorCurrentClass
            )
            is DayStatus.Break -> showStatus(
                "Перерыв. Следующая: ${status.next.subject} в ${status.next.startTime}",
                R.color.colorPastClass
            )
            DayStatus.Finished -> showStatus("Пары закончились", R.color.colorPastClass)
        }
    }

    private fun showStatus(text: String, colorRes: Int) {
        currentClassTextView.text = text
        currentClassTextView.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        currentClassTextView.visibility = View.VISIBLE
    }

    private fun formatTimeLeft(totalMinutes: Int): String {
        return when {
            totalMinutes <= 0 -> "заканчивается"
            totalMinutes < 60 -> "осталось до конца: $totalMinutes мин"
            else -> {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60

                val hoursText = when {
                    hours == 1 -> "$hours час"
                    hours in 2..4 -> "$hours часа"
                    else -> "$hours часов"
                }

                val minutesText = when {
                    minutes == 0 -> ""
                    minutes == 1 -> " $minutes минута"
                    minutes in 2..4 -> " $minutes минуты"
                    else -> " $minutes минут"
                }

                "осталось до конца: $hoursText$minutesText"
            }
        }
    }

    // Меню в Toolbar
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Показываем/скрываем пункт "Вернуться к моей" в зависимости от режима
        val returnItem = menu.findItem(R.id.menu_return_to_my)
        returnItem?.isVisible = isTemporaryView

        return true
    }

    private fun refreshSchedule() {
        Log.d("REFRESH", "Ручное обновление расписания")

        // 1. Проверяем интернет-соединение
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(
                this,
                "❌ Нет интернет-соединения\nПроверьте подключение к сети",
                Toast.LENGTH_LONG
            ).show()

            scheduleTitleTextView.text = "📖 Расписание (оффлайн)"
            return
        }

        // 2. Определяем, какую группу обновляем
        val subgroup: Int
        val interest: String

        if (isTemporaryView) {
            // Во временном режиме обновляем временную группу
            subgroup = tempSubgroup
            interest = tempInterest
            Toast.makeText(this, "🔄 Обновляем временный просмотр...", Toast.LENGTH_SHORT).show()
        } else {
            // В обычном режиме обновляем свою группу
            subgroup = prefs.getInt("subgroup", 1)
            interest = prefs.getString("interest_group", "readers") ?: "readers"
            Toast.makeText(this, "🔄 Обновляем ваше расписание...", Toast.LENGTH_SHORT).show()
        }

        Log.d("REFRESH", "Загружаем расписание для: subgroup=$subgroup, interest=$interest")

        // Результат придёт в наблюдатель viewModel.loadResult
        viewModel.loadSchedule(subgroup, interest, userInitiated = true)
    }

    private fun getReadableError(error: LoadError): String {
        return when (error) {
            LoadError.OFFLINE -> "Нет соединения с сервером"
            LoadError.NOT_FOUND -> "Расписание для этой группы не найдено"
            LoadError.BAD_DATA -> "Расписание на сервере заполнено с ошибкой"
            LoadError.UNKNOWN -> "Ошибка загрузки данных"
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_refresh -> {
                refreshSchedule()
                true
            }
            R.id.menu_change_group -> {
                showChangeGroupDialog()
                true
            }
            R.id.menu_view_other -> {
                showOtherSchedulesDialog()
                true
            }
            R.id.menu_return_to_my -> {
                returnToMyGroup()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showChangeGroupDialog() {
        val currentSubgroup = prefs.getInt("subgroup", 1)
        val currentInterest = prefs.getString("interest_group", "readers") ?: "readers"

        val currentInterestText = when (currentInterest) {
            "readers" -> "Чтецы"
            "singers" -> "Певцы"
            else -> currentInterest
        }

        AlertDialog.Builder(this)
            .setTitle("Сменить группу")
            .setMessage("Текущая группа: Подгруппа $currentSubgroup, $currentInterestText\n\nВы хотите изменить свою группу?")
            .setPositiveButton("Сменить группу") { _, _ ->
                startActivity(Intent(this, WelcomeActivity::class.java))
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showOtherSchedulesDialog() {
        val schedules = arrayOf(
            "Подгруппа 1 - Чтецы",
            "Подгруппа 1 - Певцы",
            "Подгруппа 2 - Чтецы",
            "Подгруппа 2 - Певцы"
        )

        val currentSubgroup = prefs.getInt("subgroup", 1)
        val currentInterest = prefs.getString("interest_group", "readers") ?: "readers"

        AlertDialog.Builder(this)
            .setTitle("Посмотреть расписание других групп")
            .setItems(schedules) { _, which ->
                val (subgroup, interest) = when (which) {
                    0 -> Pair(1, "readers")
                    1 -> Pair(1, "singers")
                    2 -> Pair(2, "readers")
                    else -> Pair(2, "singers")
                }

                if (subgroup == currentSubgroup && interest == currentInterest) {
                    // Из временного просмотра выбор своей группы возвращает к ней
                    if (isTemporaryView) {
                        returnToMyGroup()
                    } else {
                        Toast.makeText(this, "Это ваша текущая группа", Toast.LENGTH_SHORT).show()
                    }
                    return@setItems
                }

                // Сохраняем оригинальные настройки
                if (!isTemporaryView) {
                    originalSubgroup = currentSubgroup
                    originalInterest = currentInterest
                }

                // Устанавливаем временные настройки
                isTemporaryView = true
                tempSubgroup = subgroup
                tempInterest = interest

                // Сохраняем в SharedPreferences
                val editor = prefs.edit()
                editor.putBoolean("is_temporary_view", true)
                editor.putInt("temp_subgroup", subgroup)
                editor.putString("temp_interest", interest)
                editor.putInt("original_subgroup", currentSubgroup)
                editor.putString("original_interest", currentInterest)
                editor.apply()

                invalidateOptionsMenu()
                viewModel.loadSchedule(tempSubgroup, tempInterest)
                updateToolbarTitle()

                val interestText = when (interest) {
                    "readers" -> "Чтецы" else -> "Певцы"
                }
                Toast.makeText(this, "Загружено: Подгруппа $subgroup, $interestText", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Метод для обновления Toolbar во временном режиме
    private fun updateToolbarForTemporaryView() {
        val interestText = when (tempInterest) {
            "readers" -> "Чтецы"
            "singers" -> "Певцы"
            else -> tempInterest
        }
        val subtitle = "Гр.$tempSubgroup $interestText"
        toolbarSubtitleTextView.text = subtitle
    }

    // Метод для возврата к своей группе
    private fun returnToMyGroup() {
        if (isTemporaryView) {
            viewModel.loadSchedule(originalSubgroup, originalInterest)
            isTemporaryView = false

            // Очищаем временные настройки
            val editor = prefs.edit()
            editor.remove("is_temporary_view")
            editor.remove("temp_subgroup")
            editor.remove("temp_interest")
            editor.remove("original_subgroup")
            editor.remove("original_interest")
            editor.apply()

            invalidateOptionsMenu()
            updateToolbarTitle()
            Toast.makeText(this, "Вернулись к вашей группе", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onResume() {
        super.onResume()

        // Всегда перечитываем настройки при возвращении в Activity
        prefs = getSharedPreferences("user_settings", MODE_PRIVATE)

        Log.d("ON_RESUME", "Обновление приложения, текущий день: ${getCurrentDayOfWeek()}")

        // Сразу обновляем меню
        invalidateOptionsMenu()

        // Сразу обновляем Toolbar
        updateToolbarTitle()

        // Загружаем расписание в зависимости от режима
        if (isTemporaryView) {
            // Во временном режиме загружаем временную группу
            viewModel.loadSchedule(tempSubgroup, tempInterest)
        } else {
            // В обычном режиме загружаем свою группу
            val subgroup = prefs.getInt("subgroup", 1)
            val interest = prefs.getString("interest_group", "readers") ?: "readers"
            viewModel.loadSchedule(subgroup, interest)
        }

        // Таймер работает всегда: пользователь может переключиться на сегодня позже
        startTimeUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopTimeUpdates()
    }

    private fun startTimeUpdates() {
        stopTimeUpdates()

        updateTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (isDestroyed || isFinishing) {
                        cancel()
                        return
                    }

                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread

                        if (isSelectedDayToday()) {
                            updateCurrentClassStatus()
                            (scheduleListView.adapter as? ScheduleAdapter)?.notifyDataSetChanged()
                        }
                    }
                }
            }, 0, 60000)
        }
    }

    private fun stopTimeUpdates() {
        updateTimer?.cancel()
        updateTimer = null  // Важно: обнуляем ссылку!
    }

    override fun onDestroy() {
        stopTimeUpdates()
        super.onDestroy()
    }

    // Кастомный адаптер для списка пар
    class ScheduleAdapter(
        context: Context,
        private val classes: List<UniversityClass>,
        private val isToday: Boolean
    ) : ArrayAdapter<UniversityClass>(
        context.applicationContext,
        0,
        classes
    ) {
        // Сохраняем applicationContext для использования в методах
        private val appContext = context.applicationContext

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            // Используем LayoutInflater из parent.context
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_class, parent, false)

            val currentClass = classes[position]

            // Находим элементы в макете
            val timeTextView = view.findViewById<TextView>(R.id.classTimeTextView)
            val subjectTextView = view.findViewById<TextView>(R.id.classSubjectTextView)
            val roomTextView = view.findViewById<TextView>(R.id.classRoomTextView)
            val teacherTextView = view.findViewById<TextView>(R.id.classTeacherTextView)
            val container = view.findViewById<LinearLayout>(R.id.itemContainer)

            // Устанавливаем данные
            timeTextView.text = "${currentClass.startTime} - ${currentClass.endTime}"
            subjectTextView.text = currentClass.subject
            roomTextView.text = "Ауд. ${currentClass.room}"
            teacherTextView.text = currentClass.teacher

            if (currentClass.cancelled) {
                // 1. Зачеркиваем текст предмета
                subjectTextView.paintFlags =
                    subjectTextView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

                // 2. Меняем цвет на серый
                subjectTextView.setTextColor(Color.GRAY)
                timeTextView.setTextColor(Color.GRAY)
                roomTextView.setTextColor(Color.GRAY)
                teacherTextView.setTextColor(Color.GRAY)

                // 3. Делаем контейнер полупрозрачным
                container.alpha = 0.5f

                // 4. Можно добавить пометку "[ОТМЕНЕНО]"
                // subjectTextView.text = "[ОТМЕНЕНО] ${currentClass.subject}"

            } else {
                // Если пара НЕ отменена - сбрасываем все эффекты
                subjectTextView.paintFlags =
                    subjectTextView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                subjectTextView.setTextColor(Color.BLACK)
                timeTextView.setTextColor(Color.BLACK)
                roomTextView.setTextColor(Color.BLACK)
                teacherTextView.setTextColor(Color.BLACK)
                container.alpha = 1f
            }

            // Определяем цвета только если это сегодня
            if (isToday) {
                val now = ScheduleLogic.minutesOfDay(Calendar.getInstance())

                // Используем appContext для получения цветов
                when (ScheduleLogic.timing(currentClass, now)) {
                    ClassTiming.CURRENT -> {
                        // Текущая пара - зеленый фон
                        container.setBackgroundColor(
                            ContextCompat.getColor(appContext, R.color.colorCurrentClass)
                        )
                    }
                    ClassTiming.PAST -> {
                        // Прошедшая пара - серый фон
                        container.setBackgroundColor(
                            ContextCompat.getColor(appContext, R.color.colorPastClass)
                        )
                    }
                    ClassTiming.FUTURE -> {
                        // Будущая пара - белый фон
                        container.setBackgroundColor(
                            ContextCompat.getColor(appContext, R.color.white)
                        )
                    }
                }
            } else {
                // Для других дней - белый фон
                container.setBackgroundColor(
                    ContextCompat.getColor(appContext, R.color.white)
                )
            }

            return view
        }
    }
}