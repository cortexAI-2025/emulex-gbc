package com.emulex.gbc

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentRomAdapter(
    private val items: List<String>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<RecentRomAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uriStr = items[position]
        val name = try {
            val uri = Uri.parse(uriStr)
            uri.lastPathSegment?.substringAfterLast('/')
                ?.removeSuffix(".gbc")?.removeSuffix(".gb") ?: uriStr
        } catch (_: Exception) { uriStr }
        holder.tvName.text = name
        holder.itemView.setOnClickListener { onClick(uriStr) }
    }

    override fun getItemCount() = items.size
}
