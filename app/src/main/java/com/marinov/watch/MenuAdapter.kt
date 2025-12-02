package com.marinov.watch

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MenuOption(
    val title: String,
    val subtitle: String? = null,
    val iconRes: Int,
    val onClick: () -> Unit,
    val extraInfo: String? = null
)

class MenuAdapter(private val options: List<MenuOption>) : RecyclerView.Adapter<MenuAdapter.MenuViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_option, parent, false)
        return MenuViewHolder(view)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(options[position])
    }

    override fun getItemCount() = options.size

    class MenuViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgIcon)
        private val tvExtraInfo: TextView = itemView.findViewById(R.id.tvExtraInfo)

        fun bind(item: MenuOption) {
            tvTitle.text = item.title
            imgIcon.setImageResource(item.iconRes)

            if (item.subtitle != null) {
                tvSubtitle.text = item.subtitle
                tvSubtitle.visibility = View.VISIBLE
            } else {
                tvSubtitle.visibility = View.GONE
            }

            if (item.extraInfo != null) {
                tvExtraInfo.text = item.extraInfo
                tvExtraInfo.visibility = View.VISIBLE
            } else {
                tvExtraInfo.visibility = View.GONE
            }

            itemView.setOnClickListener { item.onClick() }
        }
    }
}