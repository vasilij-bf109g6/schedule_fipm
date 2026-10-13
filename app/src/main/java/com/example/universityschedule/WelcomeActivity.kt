package com.example.universityschedule

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class WelcomeActivity : AppCompatActivity() {

    private lateinit var settings: UserSettings
    private var selectedSubgroup = 1
    private var selectedInterest = Interest.READERS

    private lateinit var btnSubgroup1: AppCompatButton
    private lateinit var btnSubgroup2: AppCompatButton
    private lateinit var btnReaders: AppCompatButton
    private lateinit var btnSingers: AppCompatButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // Убираем ActionBar
        supportActionBar?.hide()

        settings = UserSettings(this)

        btnSubgroup1 = findViewById(R.id.btn_subgroup1)
        btnSubgroup2 = findViewById(R.id.btn_subgroup2)
        btnReaders = findViewById(R.id.btn_readers)
        btnSingers = findViewById(R.id.btn_singers)

        // При смене группы подсвечиваем текущий выбор
        settings.myGroup?.let {
            selectedSubgroup = it.subgroup
            selectedInterest = it.interest
        }
        updateSelection()

        btnSubgroup1.setOnClickListener { selectedSubgroup = 1; updateSelection() }
        btnSubgroup2.setOnClickListener { selectedSubgroup = 2; updateSelection() }
        btnReaders.setOnClickListener { selectedInterest = Interest.READERS; updateSelection() }
        btnSingers.setOnClickListener { selectedInterest = Interest.SINGERS; updateSelection() }

        findViewById<Button>(R.id.btn_continue).setOnClickListener {
            saveSettingsAndContinue()
        }
    }

    private fun updateSelection() {
        btnSubgroup1.isSelected = selectedSubgroup == 1
        btnSubgroup2.isSelected = selectedSubgroup == 2
        btnReaders.isSelected = selectedInterest == Interest.READERS
        btnSingers.isSelected = selectedInterest == Interest.SINGERS
    }

    private fun saveSettingsAndContinue() {
        settings.myGroup = Group(selectedSubgroup, selectedInterest)
        // Новая группа выбрана — временный просмотр больше не нужен
        settings.temporaryGroup = null

        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
