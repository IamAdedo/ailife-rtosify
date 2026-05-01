package com.iamadedo.phoneapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class NotificationRule(
        val id: String,
        val packageName: String,
        val mode: String, // "whitelist" or "blacklist"
        val titlePattern: String,
        val contentPattern: String
)

class NotificationRulesAdapter(
        private val onEditClick: (NotificationRule) -> Unit,
        private val onDeleteClick: (NotificationRule) -> Unit
) : RecyclerView.Adapter<NotificationRulesAdapter.ViewHolder>() {

    private var rules = listOf<NotificationRule>()

    fun setData(newRules: List<NotificationRule>) {
        rules = newRules
        notifyDataSetChanged()
    }

    fun getRules() = rules

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_notification_rule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount() = rules.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPackageName: TextView = itemView.findViewById(R.id.tvPackageName)
        private val tvMode: TextView = itemView.findViewById(R.id.tvMode)
        private val tvPatterns: TextView = itemView.findViewById(R.id.tvPatterns)
        private val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEdit)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btnDelete)

        fun bind(rule: NotificationRule) {
            tvPackageName.text = rule.packageName
            tvMode.text = when(rule.mode) {
                "whitelist" -> itemView.context.getString(R.string.rule_whitelist)
                "blacklist" -> itemView.context.getString(R.string.rule_blacklist)
                else -> rule.mode.uppercase()
            }

            val patterns = mutableListOf<String>()
            if (rule.titlePattern.isNotEmpty()) {
                patterns.add(itemView.context.getString(R.string.rule_title_filter, rule.titlePattern))
            }
            if (rule.contentPattern.isNotEmpty()) {
                patterns.add(itemView.context.getString(R.string.rule_content_filter, rule.contentPattern))
            }

            tvPatterns.text =
                    if (patterns.isEmpty()) {
                        itemView.context.getString(R.string.rule_no_filters)
                    } else {
                        patterns.joinToString("\n")
                    }

            btnEdit.setOnClickListener { onEditClick(rule) }
            btnDelete.setOnClickListener { onDeleteClick(rule) }
        }
    }
}
