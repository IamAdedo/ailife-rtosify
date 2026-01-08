package com.ailife.rtosifycompanion

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class AlarmManagementActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAdd: Button
    private lateinit var emptyState: TextView
    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var alarmManager: WatchAlarmManager
    
    companion object {
        private const val TAG = "AlarmManagementActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_management)
        
        alarmManager = WatchAlarmManager(this)
        
        initViews()
        setupRecyclerView()
        loadAlarms()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewAlarms)
        btnAdd = findViewById(R.id.btnAddAlarm)
        emptyState = findViewById(R.id.tvEmptyState)
        
        btnAdd.setOnClickListener {
            showAlarmEditor(null)
        }
    }
    
    private fun setupRecyclerView() {
        alarmAdapter = AlarmAdapter(
            onToggle = { alarm, enabled ->
                val updatedAlarm = alarm.copy(enabled = enabled)
                alarmManager.updateAlarm(updatedAlarm)
                loadAlarms()
            },
            onDelete = { alarm ->
                confirmDelete(alarm)
            },
            onEdit = { alarm ->
                showAlarmEditor(alarm)
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = alarmAdapter
    }
    
    private fun loadAlarms() {
        val alarms = alarmManager.getAllAlarms()
        
        if (alarms.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            alarmAdapter.submitList(alarms)
        }
    }
    
    private fun showAlarmEditor(existingAlarm: AlarmData?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_alarm_editor, null)
        
        val timePicker = dialogView.findViewById<TimePicker>(R.id.timePicker)
        val cbMonday = dialogView.findViewById<CheckBox>(R.id.cbMonday)
        val cbTuesday = dialogView.findViewById<CheckBox>(R.id.cbTuesday)
        val cbWednesday = dialogView.findViewById<CheckBox>(R.id.cbWednesday)
        val cbThursday = dialogView.findViewById<CheckBox>(R.id.cbThursday)
        val cbFriday = dialogView.findViewById<CheckBox>(R.id.cbFriday)
        val cbSaturday = dialogView.findViewById<CheckBox>(R.id.cbSaturday)
        val cbSunday = dialogView.findViewById<CheckBox>(R.id.cbSunday)
        val etLabel = dialogView.findViewById<EditText>(R.id.etAlarmLabel)
        
        timePicker.setIs24HourView(true)
        
        // Populate existing data if editing
        existingAlarm?.let { alarm ->
            timePicker.hour = alarm.hour
            timePicker.minute = alarm.minute
            etLabel.setText(alarm.label)
            
            cbMonday.isChecked = alarm.daysOfWeek.contains(1)
            cbTuesday.isChecked = alarm.daysOfWeek.contains(2)
            cbWednesday.isChecked = alarm.daysOfWeek.contains(3)
            cbThursday.isChecked = alarm.daysOfWeek.contains(4)
            cbFriday.isChecked = alarm.daysOfWeek.contains(5)
            cbSaturday.isChecked = alarm.daysOfWeek.contains(6)
            cbSunday.isChecked = alarm.daysOfWeek.contains(7)
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (existingAlarm == null) "Add Alarm" else "Edit Alarm")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val hour = timePicker.hour
                val minute = timePicker.minute
                val label = etLabel.text.toString()
                
                val daysOfWeek = mutableListOf<Int>()
                if (cbMonday.isChecked) daysOfWeek.add(1)
                if (cbTuesday.isChecked) daysOfWeek.add(2)
                if (cbWednesday.isChecked) daysOfWeek.add(3)
                if (cbThursday.isChecked) daysOfWeek.add(4)
                if (cbFriday.isChecked) daysOfWeek.add(5)
                if (cbSaturday.isChecked) daysOfWeek.add(6)
                if (cbSunday.isChecked) daysOfWeek.add(7)
                
                val alarm = AlarmData(
                    id = existingAlarm?.id ?: UUID.randomUUID().toString(),
                    hour = hour,
                    minute = minute,
                    enabled = true,
                    daysOfWeek = daysOfWeek,
                    label = label
                )
                
                if (existingAlarm == null) {
                    alarmManager.addAlarm(alarm)
                } else {
                    alarmManager.updateAlarm(alarm)
                }
                
                loadAlarms()
                Log.d(TAG, "Alarm saved: $alarm")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun confirmDelete(alarm: AlarmData) {
        AlertDialog.Builder(this)
            .setTitle("Delete Alarm")
            .setMessage("Delete alarm at ${String.format("%02d:%02d", alarm.hour, alarm.minute)}?")
            .setPositiveButton("Delete") { _, _ ->
                alarmManager.deleteAlarm(alarm.id)
                loadAlarms()
                Log.d(TAG, "Alarm deleted: ${alarm.id}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    // Adapter
    class AlarmAdapter(
        private val onToggle: (AlarmData, Boolean) -> Unit,
        private val onDelete: (AlarmData) -> Unit,
        private val onEdit: (AlarmData) -> Unit
    ) : RecyclerView.Adapter<AlarmAdapter.ViewHolder>() {
        
        private var alarms = emptyList<AlarmData>()
        
        fun submitList(newAlarms: List<AlarmData>) {
            alarms = newAlarms
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(alarms[position])
        }
        
        override fun getItemCount() = alarms.size
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvTime: TextView = view.findViewById(R.id.tvAlarmTime)
            private val tvDays: TextView = view.findViewById(R.id.tvAlarmDays)
            private val tvLabel: TextView = view.findViewById(R.id.tvAlarmLabel)
            private val switchEnabled: androidx.appcompat.widget.SwitchCompat = view.findViewById(R.id.switchAlarmEnabled)
            private val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteAlarm)
            
            fun bind(alarm: AlarmData) {
                tvTime.text = String.format("%02d:%02d", alarm.hour, alarm.minute)
                
                // Format days
                val dayNames = mapOf(1 to "M", 2 to "T", 3 to "W", 4 to "T", 5 to "F", 6 to "S", 7 to "S")
                if (alarm.daysOfWeek.isEmpty()) {
                    tvDays.text = "Once"
                } else {
                    tvDays.text = (1..7).joinToString(" ") { day ->
                        if (alarm.daysOfWeek.contains(day)) dayNames[day]!! else "·"
                    }
                }
                
                // Label
                if (alarm.label.isNotEmpty()) {
                    tvLabel.text = alarm.label
                    tvLabel.visibility = View.VISIBLE
                } else {
                    tvLabel.visibility = View.GONE
                }
                
                // Switch
                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = alarm.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(alarm, isChecked)
                }
                
                // Delete button
                btnDelete.setOnClickListener {
                    onDelete(alarm)
                }
                
                // Make entire item clickable for editing
                itemView.setOnClickListener {
                    onEdit(alarm)
                }
            }
        }
    }
}
