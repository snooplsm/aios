package com.aios.messaging.context

/** Bounded insertion-ordered queue where a newer operation supersedes the same key. */
internal class LatestOperationQueue<T>(private val maxSize: Int) {
    private val values = LinkedHashMap<String, T>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    fun put(key: String, value: T, protectedKey: String? = null): T? {
        require(key.isNotBlank()) { "operation key must not be blank" }
        values[key] = value
        if (values.size <= maxSize) return null
        val eldest = values.entries.firstOrNull { it.key != protectedKey }
            ?: values.entries.first()
        values.remove(eldest.key)
        return eldest.value
    }

    fun firstOrNull(): T? = values.entries.firstOrNull()?.value

    fun isCurrent(key: String, expected: T): Boolean = values[key] === expected

    fun removeIfCurrent(key: String, expected: T): Boolean {
        if (!isCurrent(key, expected)) return false
        values.remove(key)
        return true
    }

    fun removeWhere(predicate: (String) -> Boolean): List<T> {
        val removed = mutableListOf<T>()
        val iterator = values.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                removed += entry.value
                iterator.remove()
            }
        }
        return removed
    }

    fun clear() = values.clear()

    val size: Int get() = values.size
}
