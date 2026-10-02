package com.royalshuffle.android.data.local

import com.royalshuffle.android.output.*
import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPreferencesOrdinaryOutputRegistryTest {
    private fun prefs() = SharedPreferencesPlaylistPreferencesTest.FakeSharedPreferences()

    @Test fun `legacy exclusions declines selection and auth keys survive first new binding`() = runTest {
        val stored = prefs()
        stored.edit().putStringSet("managed_playlist_ids", setOf("legacy"))
            .putStringSet("declined_recovery_playlist_ids", setOf("declined"))
            .putString("selected_playlist_id", "source").putString("refresh_token", "token").apply()
        val registry = SharedPreferencesPlaylistPreferences(stored, UnconfinedTestDispatcher(testScheduler))
        assertNull(registry.boundOutput(full("source")))
        assertEquals(setOf("legacy"), registry.loadManagedPlaylistIds())
        registry.bind(full("source"), "modern", null)
        val fresh = SharedPreferencesPlaylistPreferences(stored)
        assertEquals("modern", fresh.boundOutput(full("source")))
        assertEquals(setOf("legacy","modern"), fresh.loadManagedPlaylistIds())
        assertEquals(setOf("declined"), fresh.loadDeclinedRecoveryPlaylistIds())
        assertEquals("source", fresh.loadSelectedPlaylistId())
        assertEquals("token", stored.getString("refresh_token", null))
        assertEquals(1, JSONObject(stored.getString("ordinary_output_bindings", null)!!).getInt("schema_version"))
        assertEquals(1, stored.commitCount)
    }

    @Test fun `legacy IDs have no bindings even when names suggest source or session`() {
        val stored = prefs()
        stored.edit().putStringSet("managed_playlist_ids",setOf("RND-source","RND60M-source"))
            .putString("selected_playlist_id","source").apply()
        val registry = SharedPreferencesPlaylistPreferences(stored)
        assertNull(registry.boundOutput(full("source")))
        assertNull(registry.boundOutput(timed("source",60)))
        assertFalse(stored.contains("ordinary_output_bindings"))
    }

    @Test fun `Full minutes and sources have independent durable identities`() = runTest {
        val stored = prefs()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        val identities = listOf(full("a"),timed("a",60),timed("a",30),timed("a",90),full("b"),timed("b",60))
        identities.forEachIndexed { index, identity -> registry.bind(identity,"out$index",null) }
        val fresh = SharedPreferencesPlaylistPreferences(stored)
        identities.forEachIndexed { index, identity -> assertEquals("out$index",fresh.boundOutput(identity)) }
        assertEquals(6,fresh.loadManagedPlaylistIds().size)
    }

    @Test fun `preset Custom 60 leading zeros and Artist Separation share identity`() {
        val preset = OutputSettings(mode = SessionLengthMode.SIXTY_MINUTES).snapshot()
        val custom = OutputSettings(mode = SessionLengthMode.CUSTOM,customMinutes = "060",artistSeparation = true).snapshot()
        assertEquals(OutputIdentity.from("a",preset),OutputIdentity.from("a",custom))
        assertNotEquals(full("a"),OutputIdentity.from("a",preset))
        assertNotEquals(timed("a",30),timed("a",60))
        assertNotEquals(timed("a",60),timed("b",60))
    }

    @Test fun `huge canonical minutes persist without overflow and invalid keys are rejected`() = runTest {
        val stored = prefs()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        val identity = OutputIdentity.from("source",OutputOptions(BigInteger.TEN.pow(100)))
        registry.bind(identity,"output",null)
        assertEquals("output",SharedPreferencesPlaylistPreferences(stored).boundOutput(identity))
        for (key in listOf("0","-1","060","+60"," 60","1.5","６０","")) {
            assertTrue(runCatching { OutputIdentity("source",key) }.isFailure)
        }
    }

    @Test fun `guarded replacement keeps legacy exclusions and unrelated entries`() = runTest {
        val stored = prefs()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        registry.addManagedPlaylistId("legacy")
        registry.bind(full("source"),"old",null)
        registry.bind(timed("source",60),"other",null)
        registry.bind(full("different"),"differentout",null)
        registry.bind(full("source"),"new","old")
        assertEquals("new",registry.boundOutput(full("source")))
        assertEquals("other",registry.boundOutput(timed("source",60)))
        assertEquals("differentout",registry.boundOutput(full("different")))
        assertEquals(setOf("legacy","old","new","other","differentout"),registry.loadManagedPlaylistIds())
    }

    @Test fun `expected old mismatch aborts without any persistence mutation`() = runTest {
        val stored = prefs()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        registry.bind(full("source"),"old",null)
        val before = stored.all
        assertRegistryFailure { registry.bind(full("source"),"new","wrong") }
        assertEquals(before,stored.all)
        assertEquals(1,stored.commitCount)
        assertTrue(runCatching { registry.requireCurrent(full("source"),null) }.exceptionOrNull() is OutputRegistryException)
    }

    @Test fun `output cannot equal any bound source or conflict across Full timed and sources`() = runTest {
        val stored = prefs()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        registry.bind(full("a"),"outA",null)
        registry.bind(full("b"),"outB",null)
        val before = stored.all
        for ((identity, output) in listOf(timed("a",60) to "outA",full("c") to "outB",
            timed("a",30) to "a", timed("a",90) to "b", full("outA") to "new")) {
            assertRegistryFailure { registry.bind(identity,output,null) }
            assertEquals(before,stored.all)
        }
    }

    @Test fun `legacy managed ID is never silently adopted from a creation response`() = runTest {
        val stored = prefs()
        stored.edit().putStringSet("managed_playlist_ids",setOf("legacy")).apply()
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        assertRegistryFailure { registry.bind(full("source"),"legacy",null) }
        assertNull(registry.boundOutput(full("source")))
        assertEquals(setOf("legacy"),registry.loadManagedPlaylistIds())
    }

    @Test fun `bad schema duplicate identity and conflicting IDs fail closed without rewriting`() {
        val rows = listOf(
            """{"schema_version":2,"bindings":[]}""",
            """{"schema_version":1,"bindings":[{"source_id":"s","session":"full","output_id":"s"}]}""",
            """{"schema_version":1,"bindings":[{"source_id":"s","session":"060","output_id":"o"}]}""",
            """{"schema_version":1,"bindings":[{"source_id":"s","session":"full","output_id":"o"},{"source_id":"s","session":"full","output_id":"p"}]}""",
            """{"schema_version":1,"bindings":[{"source_id":"s","session":"full","output_id":"o"},{"source_id":"t","session":"60","output_id":"o"}]}""",
            "not JSON",
        )
        rows.forEach { text ->
            val stored = prefs()
            stored.edit().putString("ordinary_output_bindings",text).apply()
            val registry = SharedPreferencesPlaylistPreferences(stored)
            assertTrue(runCatching { registry.boundOutput(full("s")) }.exceptionOrNull() is OutputRegistryException)
            assertEquals(text,stored.getString("ordinary_output_bindings",null))
        }
    }

    @Test fun `failed durable commit blocks binding use through all wrappers`() = runTest {
        val stored = prefs().apply { commitSucceeds = false }
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        assertRegistryFailure { registry.bind(full("source"),"out",null) }
        assertTrue(runCatching { SharedPreferencesPlaylistPreferences(stored).boundOutput(full("source")) }
            .exceptionOrNull() is OutputRegistryException)
    }

    @Test fun `failed commit cannot expose undurable binding even if Android updated in memory`() = runTest {
        val stored = prefs().apply { commitSucceeds = false; persistOnFailedCommit = true }
        val registry = SharedPreferencesPlaylistPreferences(stored,UnconfinedTestDispatcher(testScheduler))
        assertRegistryFailure { registry.bind(full("source"),"out",null) }
        assertTrue(stored.contains("ordinary_output_bindings"))
        assertTrue(runCatching { SharedPreferencesPlaylistPreferences(stored).boundOutput(full("source")) }
            .exceptionOrNull() is OutputRegistryException)
        assertTrue(runCatching { registry.loadManagedPlaylistIds() }.exceptionOrNull() is OutputRegistryException)
    }

    @Test fun `concurrent wrappers do not lose bindings managed IDs or recovery declines`() = runBlocking {
        val stored = prefs()
        val first = SharedPreferencesPlaylistPreferences(stored)
        val second = SharedPreferencesPlaylistPreferences(stored)
        coroutineScope {
            (0 until 20).map { index -> async(Dispatchers.Default) {
                val wrapper = if (index % 2 == 0) first else second
                wrapper.bind(full("source$index"),"out$index",null)
                wrapper.addManagedPlaylistId("legacy$index")
                wrapper.addDeclinedRecoveryPlaylistIds(setOf("declined$index"))
            } }.awaitAll()
        }
        assertEquals(40,first.loadManagedPlaylistIds().size)
        assertEquals(20,second.loadDeclinedRecoveryPlaylistIds().size)
        (0 until 20).forEach { assertEquals("out$it",first.boundOutput(full("source$it"))) }
    }

    @Test fun `concurrent same identity permits exactly one guarded registration`() = runBlocking {
        val stored = prefs()
        val first = SharedPreferencesPlaylistPreferences(stored)
        val second = SharedPreferencesPlaylistPreferences(stored)
        val results = coroutineScope { listOf(first,second).mapIndexed { index, registry ->
            async(Dispatchers.Default) { runCatching { registry.bind(full("source"),"out$index",null) } }
        }.awaitAll() }
        assertEquals(1,results.count { it.isSuccess })
        assertEquals(1,stored.commitCount)
        assertEquals(1,first.loadManagedPlaylistIds().size)
    }

    private suspend fun assertRegistryFailure(block: suspend () -> Unit) {
        assertTrue(runCatching { block() }.exceptionOrNull() is OutputRegistryException)
    }
    private fun full(source: String) = OutputIdentity(source,OutputIdentity.FULL)
    private fun timed(source: String,minutes: Int) = OutputIdentity(source,"$minutes")
}
