package com.royalshuffle.android.data.local

import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPreferencesPlaylistPreferencesTest {
    @Test
    fun `managed registration commits and survives a fresh preferences wrapper`() = runTest {
        val sharedPreferences = FakeSharedPreferences()
        val first = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(first.addManagedPlaylistId("output-id"))

        val fresh = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(setOf("output-id"), fresh.loadManagedPlaylistIds())
        assertEquals(1, sharedPreferences.commitCount)
        assertEquals(0, sharedPreferences.applyCount)
    }

    @Test
    fun `failed commit reports failure without storing managed ID`() = runTest {
        val sharedPreferences = FakeSharedPreferences().apply { commitSucceeds = false }
        val preferences = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(preferences.addManagedPlaylistId("output-id"))

        assertTrue(preferences.loadManagedPlaylistIds().isEmpty())
        assertEquals(1, sharedPreferences.commitCount)
        assertEquals(0, sharedPreferences.applyCount)
    }

    @Test
    fun `managed recovery batch commits atomically and survives recreation`() = runTest {
        val sharedPreferences = FakeSharedPreferences()
        val first = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(first.addManagedPlaylistIds(setOf("one", "two")))

        val fresh = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(setOf("one", "two"), fresh.loadManagedPlaylistIds())
        assertEquals(1, sharedPreferences.commitCount)
    }

    @Test
    fun `declined recovery IDs commit separately and survive recreation`() = runTest {
        val sharedPreferences = FakeSharedPreferences()
        val first = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(first.addDeclinedRecoveryPlaylistIds(setOf("legacy")))

        val fresh = SharedPreferencesPlaylistPreferences(
            sharedPreferences,
            UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(setOf("legacy"), fresh.loadDeclinedRecoveryPlaylistIds())
        assertTrue(fresh.loadManagedPlaylistIds().isEmpty())
        assertEquals(1, sharedPreferences.commitCount)
        assertEquals(0, sharedPreferences.applyCount)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var commitSucceeds = true
        var commitCount = 0
        var applyCount = 0

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            (values[key] as? Set<String>)?.toSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?) = update(key, value)
            override fun putStringSet(key: String?, values: Set<String>?) =
                update(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = update(key, value)
            override fun putLong(key: String?, value: Long) = update(key, value)
            override fun putFloat(key: String?, value: Float) = update(key, value)
            override fun putBoolean(key: String?, value: Boolean) = update(key, value)
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                key?.let(removals::add)
            }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            override fun commit(): Boolean {
                commitCount += 1
                if (commitSucceeds) persist()
                return commitSucceeds
            }
            override fun apply() {
                applyCount += 1
                persist()
            }

            private fun update(key: String?, value: Any?): SharedPreferences.Editor = apply {
                key?.let { updates[it] = value }
            }

            private fun persist() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }
    }
}
