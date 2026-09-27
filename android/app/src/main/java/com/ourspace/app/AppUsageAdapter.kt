package com.ourspace.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max

/**
 * 应用使用时长排行适配器。
 * 每行：应用名 + 时长 + 横向进度条（进度条最大值取列表中最长的那个应用）。
 */
class AppUsageAdapter(
    private val ctx: Context,
    private val items: MutableList<Stat> = ArrayList()
) : BaseAdapter() {

    private var maxMs: Long = 1L

    fun submit(list: List<Stat>) {
        items.clear()
        items.addAll(list)
        maxMs = max(1L, items.maxOfOrNull { it.ms } ?: 1L)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        var view = convertView
        val holder: Holder
        if (view == null) {
            view = LayoutInflater.from(ctx).inflate(R.layout.item_app_usage, parent, false)
            holder = Holder(
                tvName = view.findViewById(R.id.tvAppName),
                tvMs = view.findViewById(R.id.tvAppMs),
                pb = view.findViewById(R.id.pbApp)
            )
            view.tag = holder
        } else {
            holder = view.tag as Holder
        }

        val s = items[position]
        holder.tvName.text = s.label?.takeIf { it.isNotEmpty() } ?: s.pkg
        holder.tvMs.text = TimeUtil.humanMs(s.ms)
        holder.pb.max = 1000
        holder.pb.progress = ((s.ms * 1000L) / maxMs).toInt().coerceIn(0, 1000)
        return view
    }

    private data class Holder(
        val tvName: TextView,
        val tvMs: TextView,
        val pb: ProgressBar
    )
}
