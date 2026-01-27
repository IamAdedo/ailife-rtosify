package com.ailife.rtosify

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.graphics.BitmapFactory
import android.util.Base64

data class FileObserverRule(
    val id: String,
    val path: String,
    val types: Set<String>, // "image", "video", "audio", "text"
    val isRecursive: Boolean,
    val sendToWatch: Boolean,
    val name: String,
    val iconBase64: String? = null // For display
)

class FileObserverRulesAdapter(
    private val onEditClick: (FileObserverRule) -> Unit,
    private val onDeleteClick: (FileObserverRule) -> Unit
) : RecyclerView.Adapter<FileObserverRulesAdapter.ViewHolder>() {

    private var rules = listOf<FileObserverRule>()

    fun setData(newRules: List<FileObserverRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    fun getRules() = rules

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_observer_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount() = rules.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgIcon: ImageView = itemView.findViewById(R.id.imgRuleIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvRuleName)
        private val tvPath: TextView = itemView.findViewById(R.id.tvRulePath)
        private val tvTypes: TextView = itemView.findViewById(R.id.tvRuleTypes)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEditRule)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteRule)

        fun bind(rule: FileObserverRule) {
            tvName.text = rule.name
            tvPath.text = rule.path
            tvTypes.text = rule.types.joinToString(", ") { it.replaceFirstChar { char -> char.uppercase() } } +
                    if (rule.isRecursive) " (Recursive)" else ""

            if (!rule.iconBase64.isNullOrEmpty()) {
                try {
                    val decodedBytes = Base64.decode(rule.iconBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    imgIcon.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    imgIcon.setImageResource(android.R.drawable.ic_menu_agenda)
                }
            } else {
                imgIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            }

            btnEdit.setOnClickListener { onEditClick(rule) }
            btnDelete.setOnClickListener { onDeleteClick(rule) }
        }
    }
}
