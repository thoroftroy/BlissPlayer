package com.blissplayer.core

/**
 * Pure queue list math shared by both UIs: inserting next, appending,
 * removing and reordering never touch a player, so both platforms order
 * identically and the rules are unit-tested once.
 */
object QueueOps {
    /** Insert after the current index; returns the new queue and insert position. */
    fun <T> insertNext(queue: List<T>, currentIndex: Int, item: T): Pair<List<T>, Int> {
        if (queue.isEmpty()) return listOf(item) to 0
        val at = (currentIndex + 1).coerceIn(0, queue.size)
        return (queue.take(at) + item + queue.drop(at)) to at
    }

    fun <T> append(queue: List<T>, item: T): List<T> = queue + item

    fun <T> appendAll(queue: List<T>, items: List<T>): List<T> = queue + items

    fun <T> removeAt(queue: List<T>, index: Int): List<T> =
        if (index in queue.indices) queue.filterIndexed { i, _ -> i != index } else queue

    fun <T> move(queue: List<T>, from: Int, to: Int): List<T> {
        if (from !in queue.indices || to !in queue.indices || from == to) return queue
        val m = queue.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }
}
