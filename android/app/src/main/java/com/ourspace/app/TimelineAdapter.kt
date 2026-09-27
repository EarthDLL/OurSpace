package com.ourspace.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 「瞬间」列表的一行。
 *
 * 应用事件在这里已经被合并过：不再显示「打开微信」「关闭微信」两条，
 * 而是合成一条「微信 使用了 23 分钟」——这才是关心的信息。
 * 仍在台前、还没关闭的应用不在这里显示，概览页的「当前使用」会实时体现。
 */
data class TimelineRow(
    val ts: Long,
    val type: String,
    val text: String,
    val detail: String?
)

/** 把原始事件流（按 ts 倒序）合并成展示行 */
object TimelineRows {

    fun build(events: List<Event>): List<TimelineRow> {
        val out = ArrayList<TimelineRow>(events.size)
        // 已经被某条 app_close 配对消费掉的 app_open 下标
        val consumedOpen = HashSet<Int>()

        for ((i, e) in events.withIndex()) {
            when (e.type) {
                EventType.APP_CLOSE -> {
                    // 列表按时间倒序，所以配对的那条「打开」在更靠后的位置
                    var j = i + 1
                    while (j < events.size) {
                        val o = events[j]
                        if (o.type == EventType.APP_OPEN && o.pkg == e.pkg && !consumedOpen.contains(j)) {
                            consumedOpen.add(j)
                            break
                        }
                        j++
                    }
                    val dur = if (j < events.size) e.ts - events[j].ts else -1L
                    // 不足 1 秒的会话没有信息量（历史上是应用内部 Activity 抖动造成的），不显示
                    if (dur in 1L..999L) continue
                    val name = e.label ?: "未知应用"
                    val text = if (dur > 0L) "$name 使用了 ${TimeUtil.humanMs(dur)}" else "关闭了「$name」"
                    out.add(TimelineRow(e.ts, EventType.APP_CLOSE, text, e.pkg))
                }

                EventType.APP_OPEN -> {
                    // 一律不单独显示：
                    //  - 已经被 app_close 配对的，时长会体现在「XX 使用了 N 分钟」那一行；
                    //  - 还没关闭的（仍在前台），概览页的「当前使用：XX」已经实时显示了，
                    //    放在瞬间列表里只是一条噪音，而且常常会是「正在使用『桌面』」这种无意义内容。
                }

                else -> out.add(TimelineRow(e.ts, e.type, EventType.describe(e.type, e.label), null))
            }
        }
        return out
    }
}

/**
 * 瞬间列表适配器。
 * 每行：时间 + 事件描述（解锁 / 亮屏 / 熄屏 / 某应用使用了多久 / 开机）+ 包名。
 */
class TimelineAdapter(
    private val ctx: Context,
    private val items: MutableList<TimelineRow> = ArrayList()
) : BaseAdapter() {

    fun submit(list: List<TimelineRow>) {
        // 内容没变就不重建列表：10 秒一次的刷新不能打断正在滚动的位置
        if (items == list) return
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var view = convertView
        val holder: Holder
        if (view == null) {
            view = LayoutInflater.from(ctx).inflate(R.layout.item_timeline, parent, false)
            holder = Holder(
                tvTime = view.findViewById(R.id.tvTime),
                tvText = view.findViewById(R.id.tvEventText),
                tvDetail = view.findViewById(R.id.tvEventDetail)
            )
            view.tag = holder
        } else {
            holder = view.tag as Holder
        }

        val row = items[position]
        holder.tvTime.text = TimeUtil.timeOf(row.ts)
        holder.tvText.text = row.text
        // 每种行为一个背景色，扫一眼就能分辨（列表行会被复用，所以每次都重设）
        view.setBackgroundColor(ctx.getColor(bgOf(row.type)))

        // 应用类事件补一行包名，便于确认是哪个应用
        val detail = row.detail ?: ""
        if (detail.isEmpty()) {
            holder.tvDetail.visibility = View.GONE
        } else {
            holder.tvDetail.visibility = View.VISIBLE
            holder.tvDetail.text = detail
        }
        return view
    }

    private data class Holder(
        val tvTime: TextView,
        val tvText: TextView,
        val tvDetail: TextView
    )

    /** 行为 -> 背景色 */
    private fun bgOf(type: String): Int = when (type) {
        EventType.UNLOCK -> R.color.ev_unlock
        EventType.SCREEN_ON -> R.color.ev_screen_on
        EventType.SCREEN_OFF -> R.color.ev_screen_off
        EventType.APP_OPEN -> R.color.ev_app_open
        EventType.APP_CLOSE -> R.color.ev_app_close
        EventType.BOOT -> R.color.ev_boot
        else -> R.color.white
    }
}
