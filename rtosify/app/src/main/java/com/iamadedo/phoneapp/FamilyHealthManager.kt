package com.iamadedo.phoneapp

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Family Health Community — phone-side manager.
 *
 * Stores health snapshots from registered family members and forwards them
 * to the watch on request or when a new snapshot arrives from another device.
 *
 * Flow:
 *  1. User registers family members by device/alias on the phone
 *  2. When FAMILY_HEALTH_REQUEST arrives from watch → phone looks up the
 *     latest snapshot for that member and sends FAMILY_MEMBER_HEALTH back
 *  3. When this phone's own health data updates → broadcast snapshot to all
 *     paired devices that have opted in (family sharing enabled)
 *
 * Privacy: data never leaves the local Bluetooth/WiFi link — no cloud involved.
 */
class FamilyHealthManager(private val context: Context) {

    companion object {
        private const val PREFS = "family_health"
        private const val KEY_MEMBERS = "members"
        private const val KEY_SNAPSHOTS = "snapshots"
        private const val SNAPSHOT_TTL_MS = 6 * 60 * 60_000L   // 6 hours
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Member management ─────────────────────────────────────────────────────

    data class FamilyMember(
        val id: String,
        val name: String,
        val sharingEnabled: Boolean = true
    )

    fun getMembers(): List<FamilyMember> {
        val json = prefs.getString(KEY_MEMBERS, "[]") ?: "[]"
        return gson.fromJson(json, object : TypeToken<List<FamilyMember>>() {}.type)
    }

    fun addMember(member: FamilyMember) {
        val members = getMembers().toMutableList()
        members.removeAll { it.id == member.id }
        members.add(member)
        prefs.edit().putString(KEY_MEMBERS, gson.toJson(members)).apply()
    }

    fun removeMember(memberId: String) {
        val members = getMembers().toMutableList()
        members.removeAll { it.id == memberId }
        prefs.edit().putString(KEY_MEMBERS, gson.toJson(members)).apply()
        removeSnapshot(memberId)
    }

    // ── Snapshot storage ──────────────────────────────────────────────────────

    fun storeSnapshot(data: FamilyHealthData) {
        val snapshots = getAllSnapshots().toMutableMap()
        snapshots[data.memberId] = data
        prefs.edit().putString(KEY_SNAPSHOTS, gson.toJson(snapshots)).apply()
    }

    fun getSnapshot(memberId: String): FamilyHealthData? {
        val snapshots = getAllSnapshots()
        val snap = snapshots[memberId] ?: return null
        // Expire stale snapshots
        return if (System.currentTimeMillis() - snap.lastUpdated < SNAPSHOT_TTL_MS) snap else null
    }

    fun getAllFreshSnapshots(): List<FamilyHealthData> {
        val now = System.currentTimeMillis()
        return getAllSnapshots().values.filter { now - it.lastUpdated < SNAPSHOT_TTL_MS }
    }

    private fun getAllSnapshots(): Map<String, FamilyHealthData> {
        val json = prefs.getString(KEY_SNAPSHOTS, "{}") ?: "{}"
        return try {
            gson.fromJson(json, object : TypeToken<Map<String, FamilyHealthData>>() {}.type)
        } catch (e: Exception) { emptyMap() }
    }

    private fun removeSnapshot(memberId: String) {
        val snapshots = getAllSnapshots().toMutableMap()
        snapshots.remove(memberId)
        prefs.edit().putString(KEY_SNAPSHOTS, gson.toJson(snapshots)).apply()
    }

    // ── Snapshot builder from local health data ────────────────────────────────

    /**
     * Build a snapshot of this device's own health to share with family.
     * Called after each HEALTH_DATA_UPDATE from the watch.
     */
    fun buildSelfSnapshot(
        deviceId: String,
        deviceName: String,
        hrBpm: Int?,
        bloodOxygen: Int?,
        steps: Int,
        sleepScore: Int?
    ): FamilyHealthData = FamilyHealthData(
        memberId    = deviceId,
        memberName  = deviceName,
        heartRateBpm = hrBpm,
        bloodOxygen = bloodOxygen,
        steps       = steps,
        sleepScore  = sleepScore
    )
}
