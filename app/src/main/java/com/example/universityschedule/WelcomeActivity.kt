package com.example.universityschedule

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class WelcomeActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var selectedSubgroup = 1
    private var selectedInterest = "readers"

    private lateinit var btnSubgroup1: AppCompatButton
    private lateinit var btnSubgroup2: AppCompatButton
    private lateinit var btnReaders: AppCompatButton
    private lateinit var btnSingers: AppCompatButton
    private lateinit var btnContinue: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Убираем ActionBar
        supportActionBar?.hide()

        prefs = getSharedPreferences("user_settings", MODE_PRIVATE)

        // Находим все View
        btnSubgroup1 = findViewById(R.id.btn_subgroup1)
        btnSubgroup2 = findViewById(R.id.btn_subgroup2)
        btnReaders = findViewById(R.id.btn_readers)
        btnSingers = findViewById(R.id.btn_singers)
        btnContinue = findViewById(R.id.btn_continue)

        // Устанавливаем начальные состояния
        updateButtonSelection(btnSubgroup1, true)
        updateButtonSelection(btnReaders, true)

        // Обработчики нажатий
        btnSubgroup1.setOnClickListener {
            selectedSubgroup = 1
            updateButtonSelection(btnSubgroup1, true)
            updateButtonSelection(btnSubgroup2, false)
        }

        btnSubgroup2.setOnClickListener {
            selectedSubgroup = 2
            updateButtonSelection(btnSubgroup1, false)
            updateButtonSelection(btnSubgroup2, true)
        }

        btnReaders.setOnClickListener {
            selectedInterest = "readers"
            updateButtonSelection(btnReaders, true)
            updateButtonSelection(btnSingers, false)
        }

        btnSingers.setOnClickListener {
            selectedInterest = "singers"
            updateButtonSelection(btnReaders, false)
            updateButtonSelection(btnSingers, true)
        }

        btnContinue.setOnClickListener {
            saveSettingsAndContinue()
        }
    }

    private fun updateButtonSelection(button: AppCompatButton, isSelected: Boolean) {
        button.isSelected = isSelected
        // Убрали анимацию масштабирования, чтобы не обрезались края
    }

    private fun saveSettingsAndContinue() {
        val editor = prefs.edit()
        editor.putInt("subgroup", selectedSubgroup)
        editor.putString("interest_group", selectedInterest)
        editor.apply()

        // Переход к основному экрану
        val intent = Intent(this@WelcomeActivity, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}