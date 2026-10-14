package com.encore.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Last known bio / location of people, kept in memory so a profile screen can draw its final
 * layout on the very first frame instead of filling in a moment later.
 * The same data is also stored in the database (ProfileEntity) for the next app start.
 */
object ProfileCache {
    private val map = ConcurrentHashMap<String, ProfileEntity>()

    fun get(userId: String): ProfileEntity? = map[userId]

    fun put(p: ProfileEntity) {
        map[p.userId] = p
    }

    fun clear() {
        map.clear()
    }
}
