package com.iamadedo.phoneapp

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
    val extraInfo: String? = null,
    val titleRes: Int = 0,
    var isEnabled: Boolean = true
)

class MenuAdapter(val menuItems: MutableList<MenuOption>) : RecyclerView.Adapter<MenuAdapter.MenuViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_menu_option, parent, false)
        return MenuViewHolder(view)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        holder.bind(menuItems[position])
    }

    override fun getItemCount() = menuItems.size

    fun updateOptions(newOptions: List<MenuOption>) {
        menuItems.clear()
        menuItems.addAll(newOptions)
        notifyDataSetChanged()
    }

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

            // Handle enabled/disabled state
            itemView.isEnabled = item.isEnabled
            itemView.alpha = if (item.isEnabled) 1.0f else 0.5f

            itemView.setOnClickListener { 
                if (item.isEnabled) {
                    item.onClick()
                }
            }
        }
    }
}