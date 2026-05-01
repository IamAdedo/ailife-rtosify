package com.iamadedo.phoneapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Phone-side medication reminder manager.
 *
 * Stores a list of MedEntry objects in SharedPreferences.
 * Schedules exact alarms via AlarmManager.
 * On alarm fire → sends MED_REMINDER message to watch via BluetoothService.
 * When the user confirms (MED_CONFIRM received from watch) → logs adherence.
 *
 * Data model:
 *  MedEntry: id, name, dosage, scheduledTimeMs (daily recurrence)
 *  AdherenceRecord: medId, date (yyyyMMdd), action ("TAKEN"/"SKIPPED")
 */

data class MedEntry(
    val id: String,
    val name: String,
    val dosage: String,
    val scheduledHour: Int,        // 0-23
    val scheduledMinute: Int,      // 0-59
    val enabled: Boolean = true
)

data class AdherenceRecord(
    val medId: String,
    val date: String,              // yyyyMMdd
    val action: String             // "TAKEN" or "SKIPPED"
)

class MedicationManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "medication_prefs"
        private const val KEY_MEDS = "medications"
        private const val KEY_ADHERENCE = "adherence"
        const val ACTION_MED_ALARM = "com.iamadedo.phoneapp.MED_ALARM"
        const val EXTRA_MED_ID = "med_id"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ── CRUD ──────────────────────────────────────────────────────────────────

    fun getMedications(): List<MedEntry> {
        val json = prefs.getString(KEY_MEDS, "[]") ?: "[]"
        return gson.fromJson(json, object : TypeToken<List<MedEntry>>() {}.type)
    }

    fun addMedication(entry: MedEntry) {
        val meds = getMedications().toMutableList()
        meds.removeAll { it.id == entry.id }
        meds.add(entry)
        prefs.edit().putString(KEY_MEDS, gson.toJson(meds)).apply()
        if (entry.enabled) scheduleAlarm(entry)
    }

    fun removeMedication(medId: String) {
        val meds = getMedications().toMutableList()
        meds.removeAll { it.id == medId }
        prefs.edit().putString(KEY_MEDS, gson.toJson(meds)).apply()
        cancelAlarm(medId)
    }

    fun updateMedication(entry: MedEntry) {
        cancelAlarm(entry.id)
        addMedication(entry)
    }

    // ── Adherence ─────────────────────────────────────────────────────────────

    fun getAdherence(): List<AdherenceRecord> {
        val json = prefs.getString(KEY_ADHERENCE, "[]") ?: "[]"
        return gson.fromJson(json, object : TypeToken<List<AdherenceRecord>>() {}.type)
    }

    fun recordAdherence(medId: String, action: String) {
        val records = getAdherence().toMutableList()
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        records.removeAll { it.medId == medId && it.date == today }
        records.add(AdherenceRecord(medId, today, action))
        prefs.edit().putString(KEY_ADHERENCE, gson.toJson(records)).apply()
    }

    fun adherenceRatePercent(medId: String, lastDays: Int = 7): Int {
        val records = getAdherence().filter { it.medId == medId }
        val cal = java.util.Calendar.getInstance()
        var taken = 0
        var total = 0
        for (i in 0 until lastDays) {
            val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(cal.time)
            val record = records.firstOrNull { it.date == date }
            if (record != null) {
                total++
                if (record.action == "TAKEN") taken++
            }
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return if (total == 0) 0 else (taken * 100 / total)
    }

    // ── Alarm scheduling ──────────────────────────────────────────────────────

    fun scheduleAll() {
        getMedications().filter { it.enabled }.forEach { scheduleAlarm(it) }
    }

    private fun scheduleAlarm(entry: MedEntry) {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, entry.scheduledHour)
            set(java.util.Calendar.MINUTE, entry.scheduledMinute)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis < System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        val intent = PendingIntent.getBroadcast(
            context,
            entry.id.hashCode(),
            Intent(ACTION_MED_ALARM).putExtra(EXTRA_MED_ID, entry.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            intent
        )
    }

    private fun cancelAlarm(medId: String) {
        val intent = PendingIntent.getBroadcast(
            context,
            medId.hashCode(),
            Intent(ACTION_MED_ALARM),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(intent)
    }
}

/**
 * BroadcastReceiver that fires when a medication alarm triggers.
 * Sends the reminder to the watch via BluetoothService.
 */
class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MedicationManager.ACTION_MED_ALARM) return
        val medId = intent.getStringExtra(MedicationManager.EXTRA_MED_ID) ?: return
        val manager = MedicationManager(context)
        val med = manager.getMedications().firstOrNull { it.id == medId } ?: return

        val serviceIntent = Intent(context, BluetoothService::class.java).apply {
            action = "com.iamadedo.phoneapp.SEND_MED_REMINDER"
            putExtra(MedicationManager.EXTRA_MED_ID, medId)
            putExtra("med_name", med.name)
            putExtra("med_dosage", med.dosage)
        }
        context.startService(serviceIntent)
    }
}
