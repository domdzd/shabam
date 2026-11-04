package app.nanotune.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    @Test
    fun lastWriterWinsAndTiesKeepLocal() {
        assertTrue(SyncMerge.incomingWins(localUpdatedAt = null, incomingUpdatedAt = 1))
        assertTrue(SyncMerge.incomingWins(localUpdatedAt = 1, incomingUpdatedAt = 2))
        assertFalse(SyncMerge.incomingWins(localUpdatedAt = 2, incomingUpdatedAt = 2))
        assertFalse(SyncMerge.incomingWins(localUpdatedAt = 3, incomingUpdatedAt = 2))
    }

    @Test
    fun batching() {
        assertEquals(listOf(listOf(1, 2), listOf(3)), SyncMerge.batches(listOf(1, 2, 3), maxBatch = 2))
        assertTrue(SyncMerge.batches(emptyList<Int>()).isEmpty())
    }

    @Test
    fun deviceSpecificSettingsAreNotSynced() {
        assertFalse("server_url" in SyncMerge.SYNCED_SETTING_KEYS)
        assertTrue("theme" in SyncMerge.SYNCED_SETTING_KEYS)
    }
}
