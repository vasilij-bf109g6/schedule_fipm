package com.example.universityschedule

import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class ScheduleAdapter : ListAdapter<UniversityClass, ScheduleAdapter.ViewHolder>(DIFF) {

    // Подсветка текущей и прошедших пар нужна только для сегодняшнего дня
    private var isToday = false

    fun submit(classes: List<UniversityClass>, isToday: Boolean) {
        val todayChanged = this.isToday != isToday
        this.isToday = isToday
        submitList(classes) {
            if (todayChanged) refreshTiming()
        }
    }

    // Перекрашивает карточки после смены минуты
    fun refreshTiming() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_class, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val now = ScheduleLogic.minutesOfDay(Calendar.getInstance())
        holder.bind(getItem(position), isToday, now)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: View = view.findViewById(R.id.itemContainer)
        private val timeTextView: TextView = view.findViewById(R.id.classTimeTextView)
        private val subjectTextView: TextView = view.findViewById(R.id.classSubjectTextView)
        private val roomTextView: TextView = view.findViewById(R.id.classRoomTextView)
        private val teacherTextView: TextView = view.findViewById(R.id.classTeacherTextView)

        fun bind(item: UniversityClass, isToday: Boolean, nowMinutes: Int) {
            val context = itemView.context

            timeTextView.text = context.getString(R.string.class_time, item.startTime, item.endTime)
            subjectTextView.text = item.subject
            roomTextView.text = context.getString(R.string.class_room, item.room)
            teacherTextView.text = item.teacher

            if (item.cancelled) {
                // Отменённая пара: зачёркнута, серая и полупрозрачная
                subjectTextView.paintFlags = subjectTextView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                val gray = ContextCompat.getColor(context, R.color.dayNormal)
                listOf(subjectTextView, timeTextView, roomTextView, teacherTextView).forEach { it.setTextColor(gray) }
                container.alpha = 0.5f
            } else {
                subjectTextView.paintFlags = subjectTextView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                subjectTextView.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
                timeTextView.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                roomTextView.setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                teacherTextView.setTextColor(ContextCompat.getColor(context, R.color.textSecondary))
                container.alpha = 1f
            }

            val cardColor = if (!isToday) {
                R.color.surface
            } else when (ScheduleLogic.timing(item, nowMinutes)) {
                ClassTiming.CURRENT -> R.color.colorCurrentClass
                ClassTiming.PAST -> R.color.colorPastClass
                ClassTiming.FUTURE -> R.color.surface
            }
            // Tint сохраняет скругление и рамку card_background
            ViewCompat.setBackgroundTintList(container, ColorStateList.valueOf(ContextCompat.getColor(context, cardColor)))
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<UniversityClass>() {
            override fun areItemsTheSame(oldItem: UniversityClass, newItem: UniversityClass) =
                oldItem.dayOfWeek == newItem.dayOfWeek && oldItem.startTime == newItem.startTime && oldItem.subject == newItem.subject

            override fun areContentsTheSame(oldItem: UniversityClass, newItem: UniversityClass) = oldItem == newItem
        }
    }
}
