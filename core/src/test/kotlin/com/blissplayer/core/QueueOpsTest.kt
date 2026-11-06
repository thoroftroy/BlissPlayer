package com.blissplayer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOpsTest {
    @Test fun insertNextLandsAfterCurrent() {
        val (q, at) = QueueOps.insertNext(listOf(1, 2, 3), 0, 9)
        assertEquals(listOf(1, 9, 2, 3), q)
        assertEquals(1, at)
    }

    @Test fun insertNextIntoEmptyStartsQueue() {
        val (q, at) = QueueOps.insertNext(emptyList(), -1, 9)
        assertEquals(listOf(9), q)
        assertEquals(0, at)
    }

    @Test fun insertNextAtEndAppends() {
        val (q, at) = QueueOps.insertNext(listOf(1, 2), 5, 9)
        assertEquals(listOf(1, 2, 9), q)
        assertEquals(2, at)
    }

    @Test fun removeAndMoveBehave() {
        assertEquals(listOf(1, 3), QueueOps.removeAt(listOf(1, 2, 3), 1))
        assertEquals(listOf(1, 2, 3), QueueOps.removeAt(listOf(1, 2, 3), 9))
        assertEquals(listOf(2, 3, 1), QueueOps.move(listOf(1, 2, 3), 0, 2))
        assertEquals(listOf(1, 2, 3), QueueOps.move(listOf(1, 2, 3), 1, 1))
    }
}
