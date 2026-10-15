package com.example.universityschedule

import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Страницы ViewPager2: по одной на день текущей и следующей недели (см. ScheduleLogic.PAGE_COUNT).
// Каждая страница — свой список пар со своим ScheduleAdapter.
class DayPagerAdapter(
    private val scheduleForDay: (dayOfWeek: Int) -> List<UniversityClass>,
    private val isTodayPage: (page: Int) -> Boolean
) : RecyclerView.Adapter<DayPagerAdapter.PageHolder>() {

    // Привязанные сейчас страницы — чтобы раз в минуту перекрасить карточки без перепривязки
    private val boundPages = mutableMapOf<Int, PageHolder>()

    override fun getItemCount(): Int = ScheduleLogic.PAGE_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        // ViewPager2 требует, чтобы страница занимала его целиком
        val list = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(parent.context)
        }
        return PageHolder(list)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        boundPages.values.remove(holder)
        boundPages[position] = holder
        holder.scheduleAdapter.submit(scheduleForDay(ScheduleLogic.dayForPage(position)), isTodayPage(position))
    }

    override fun onViewRecycled(holder: PageHolder) {
        boundPages.values.remove(holder)
    }

    // Расписание или «сегодня» изменились — перепривязываем все страницы
    fun refreshAll() {
        notifyItemRangeChanged(0, itemCount)
    }

    // Сменилась минута — перекрашиваем текущую и прошедшие пары
    fun refreshTiming() {
        boundPages.values.forEach { it.scheduleAdapter.refreshTiming() }
    }

    class PageHolder(list: RecyclerView) : RecyclerView.ViewHolder(list) {
        val scheduleAdapter = ScheduleAdapter().also { list.adapter = it }
    }
}
