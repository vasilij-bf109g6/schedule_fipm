package com.example.universityschedule

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    private lateinit var settings: UserSettings
    private lateinit var groupsContainer: LinearLayout
    private lateinit var btnContinue: Button

    private var groups: List<Group> = emptyList()
    private var selectedGroup: Group? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Убираем ActionBar
        supportActionBar?.hide()

        settings = UserSettings(this)
        groupsContainer = findViewById(R.id.groups_container)
        btnContinue = findViewById(R.id.btn_continue)

        // При смене группы подсвечиваем текущий выбор
        selectedGroup = settings.myGroup

        // Сразу показываем встроенный список, затем заменяем его списком из Firestore
        showGroups(defaultGroups(this))
        lifecycleScope.launch {
            ScheduleRepository().loadGroups()?.let { loaded ->
                settings.updateNames(loaded)
                showGroups(loaded)
            }
        }

        btnContinue.setOnClickListener { saveSettingsAndContinue() }
    }

    private fun showGroups(newGroups: List<Group>) {
        groups = newGroups
        // Выбранной группы нет в новом списке — сбрасываем выбор, пусть пользователь выберет заново
        selectedGroup = newGroups.firstOrNull { it.isSameAs(selectedGroup) }

        groupsContainer.removeAllViews()
        newGroups.forEachIndexed { index, group ->
            val button = AppCompatButton(this).apply {
                text = group.name
                isAllCaps = false
                textSize = 16f
                setTextColor(ContextCompat.getColorStateList(context, R.color.button_text_selector))
                background = ContextCompat.getDrawable(context, R.drawable.button_group_selector)
                stateListAnimator = null
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)).apply {
                    if (index > 0) topMargin = dpToPx(8)
                }
                setOnClickListener {
                    selectedGroup = group
                    updateSelection()
                }
            }
            groupsContainer.addView(button)
        }
        updateSelection()
    }

    private fun updateSelection() {
        groups.forEachIndexed { index, group ->
            groupsContainer.getChildAt(index)?.isSelected = group.isSameAs(selectedGroup)
        }
        // Без выбора продолжить нельзя
        btnContinue.isEnabled = selectedGroup != null
        btnContinue.alpha = if (selectedGroup != null) 1f else 0.5f
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun saveSettingsAndContinue() {
        val newGroup = selectedGroup ?: return
        val groupChanged = !newGroup.isSameAs(settings.myGroup)
        settings.myGroup = newGroup
        if (groupChanged) {
            // Напоминания по старой группе больше не нужны; новая загрузится на главном экране
            ReminderScheduler.onGroupChanged(this)
        }
        // Новая группа выбрана — временный просмотр больше не нужен
        settings.temporaryGroup = null

        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
