package com.ailife.rtosify

import java.util.concurrent.ConcurrentHashMap

object NotificationCache {
    private val cache = ConcurrentHashMap<String, NotificationData>()

    fun put(key: String, data: NotificationData) {
        cache[key] = data
    }

    fun get(key: String): NotificationData? {
        return cache[key]
    }

    fun remove(key: String): NotificationData? {
        return cache.remove(key)
    }
    
    fun clear() {
        cache.clear()
    }
}
