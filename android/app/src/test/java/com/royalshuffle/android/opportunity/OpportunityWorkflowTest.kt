package com.royalshuffle.android.opportunity

import com.royalshuffle.android.data.remote.*
import com.royalshuffle.android.output.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OpportunityWorkflowTest {
    @Test fun `resume checks expected pending operation after acquiring mutation lease`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("interrupted")
        runCatching { f.generate() }
        val old = f.pending()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val owner = launch {
            f.coordinator.workflow { entered.complete(Unit); release.await() }
        }
        entered.await()
        val resumed = async {
            runCatching { f.workflow().resumePendingSession("source", f.names, expectedOperationId = old.operationId) }
        }
        runCurrent()
        f.store.abandonPending("source", old.operationId)
        val candidate = f.planner.candidate(f.api.sourceItems)
        f.store.stage(f.planner.prepare("source", candidate), "Replacement", candidate)
        val before = f.disk.text
        val events = f.api.events.toList()
        release.complete(Unit)
        owner.join()
        assertTrue(resumed.await().exceptionOrNull() is OpportunityPendingException)
        assertEquals(before, f.disk.text)
        assertEquals(events, f.api.events)
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable = try { block(); throw AssertionError("Expected failure") }
        catch (error: Exception) { error }

    @Test fun `intent output attempt and each prior receipt durable before remote mutation`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.sourceItems = OpportunityTestApi.items(201, 1)
        f.api.observe = { event ->
            if (event == "create") {
                assertEquals(OpportunityPhase.CREATING, f.pending().phase)
                assertEquals(201, f.pending().uris.size)
                assertEquals("Source - Deal 1", f.pending().creationName)
            }
            if (event.startsWith("clear:")) {
                assertEquals("deal1", f.pending().outputId)
                assertEquals(OpportunityPhase.POPULATING, f.pending().phase)
                assertTrue(f.pending().attempt!!.receipts.isEmpty())
            }
            if (event.startsWith("append:")) assertEquals(f.api.appendCount + 1, f.pending().attempt!!.receipts.size)
        }
        val result = f.generate()
        assertEquals(listOf("append:deal1:100", "append:deal1:100", "append:deal1:1"), f.api.events.filter { it.startsWith("append") })
        assertEquals(0, result.progress.remainingUniqueCount)
        assertEquals(1, result.progress.completedDealCount)
        assertNull(f.store.load().sources.getValue("source").pending)
        assertTrue(f.preferences.managed.isEmpty())
        assertTrue(f.preferences.bindings.isEmpty())
    }

    @Test fun `known rejected creation retries same prepared session without source reload or redraw`() = runTest {
        for (status in listOf(400, 401, 403, 429)) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.createFailure = SpotifyWebApiException(WebApiFailureCategory.OTHER, status)
            assertTrue(failure { f.generate() } is SpotifyWebApiException)
            val p = f.pending()
            assertEquals(OpportunityPhase.STAGED, p.phase)
            f.api.sourceItems = OpportunityTestApi.items(1, 1, "changed")
            f.api.createFailure = null
            assertEquals(p.uris, f.resume().uris)
            assertEquals(1, f.shuffles)
            assertEquals(1, f.api.sourceLoads)
            assertEquals(1, f.prompts.size)
        }
    }

    @Test fun `lost creation server malformed acknowledgement and cancellation never recreate`() = runTest {
        for (error in listOf(IOException("timeout"), SpotifyWebApiException(WebApiFailureCategory.SERVER, 500),
            SpotifyWebApiException(WebApiFailureCategory.INVALID_RESPONSE, 201), CancellationException("interrupted"))) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.createFailure = error
            failure { f.generate() }
            assertEquals(OpportunityPhase.CREATING, f.pending().phase)
            f.api.createFailure = null
            val restarted = f.restart()
            assertTrue(failure { restarted.resume() } is OpportunityUnknownCreationException)
            assertEquals(1, f.api.creationCalls)
            assertFalse(f.api.events.any { it.startsWith("clear") || it.startsWith("append") })
        }
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.loseCreate = true
        failure { f.generate() }
        assertEquals(1, f.api.contents.size)
        assertTrue(failure { f.restart().resume() } is OpportunityUnknownCreationException)
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `failed output ID save blocks population and restart remains unknown creation`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.disk.fail = { it.toString().contains("OUTPUT_KNOWN") }
        val error = failure { f.generate() }
        assertTrue(error is OpportunityUnknownCreationException)
        assertEquals("deal1", (error as OpportunityUnknownCreationException).returnedOutputId)
        assertFalse(f.api.events.any { it.startsWith("clear") })
        assertTrue(failure { f.restart().resume() } is OpportunityUnknownCreationException)
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `cancel blank or canceled name request cause no remote mutation and preserve old state`() = runTest {
        for (name in listOf<String?>(null, " ")) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            val old = f.planner.candidate(f.api.sourceItems)
            f.store.beginRotation("source", old)
            val before = f.disk.text
            val result = runCatching { f.workflow().generateNextSession(f.source, CreationNameProvider { name }) }
            if (name == null) assertNull(result.getOrThrow()) else assertTrue(result.isFailure)
            assertEquals(before, f.disk.text)
            assertEquals(0, f.api.creationCalls)
        }
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        failure { f.workflow().generateNextSession(f.source, CreationNameProvider { throw CancellationException() }) }
        assertNull(f.disk.text)
        assertEquals(0, f.api.creationCalls)
    }

    @Test fun `failed stage creation intent or attempt save prevents corresponding remote write`() = runTest {
        for ((phase, forbidden) in listOf("STAGED" to "create", "CREATING" to "create", "POPULATING" to "clear:")) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.disk.fail = { it.toString().contains(phase) }
            failure { f.generate() }
            assertFalse(f.api.events.any { it.startsWith(forbidden) })
        }
    }

    @Test fun `failed receipt save stops before next append and exact recovery does not rewrite`() = runTest {
        for (receiptCount in listOf(1, 2, 3)) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.sourceItems = OpportunityTestApi.items(101, 1)
            f.disk.fail = { root ->
                val p = root.getJSONObject("sources").getJSONObject("source").getJSONObject("pending")
                !p.isNull("attempt") && p.getJSONObject("attempt").getJSONArray("receipts").length() == receiptCount
            }
            failure { f.generate() }
            assertEquals(receiptCount - 1, f.api.appendCount)
            val restarted = f.restart()
            if (receiptCount < 3) f.api.rawOverride = restarted.pending().uris
            val events = f.api.events.size
            val result = restarted.resume()
            assertEquals(1, result.progress.completedDealCount)
            assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") })
        }
    }

    @Test fun `ambiguous last append exact readback finalizes without rewrite or redraw after restart`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost acknowledgement")
        f.api.loseAppend = true
        failure { f.generate(true) }
        val p = f.pending()
        assertEquals(1, f.api.appendCount)
        val events = f.api.events.size
        val restarted = f.restart()
        f.api.sourceItems = OpportunityTestApi.items(1, 1, "newsource")
        f.api.appendFailure = null
        val result = restarted.resume()
        assertEquals(p.uris, result.uris)
        assertEquals(0, restarted.shuffles)
        assertEquals(1, f.api.sourceLoads)
        assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") || it == "create" })
        assertTrue(f.api.events.drop(events).contains("raw"))
    }

    @Test fun `valid mismatch clears and rebuilds identical multi batch saved order`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.sourceItems = OpportunityTestApi.items(151, 1)
        f.api.failAppendNumber = 2
        f.api.appendFailure = IOException("second append unknown")
        failure { f.generate() }
        val p = f.pending()
        assertEquals(100, f.api.contents.getValue("deal1").size)
        f.api.appendFailure = null
        val restarted = f.restart()
        val events = f.api.events.size
        val result = restarted.resume()
        assertEquals(p.uris, result.uris)
        assertEquals(p.uris, f.api.contents.getValue("deal1"))
        assertEquals(listOf("clear:deal1", "append:deal1:100", "append:deal1:51"),
            f.api.events.drop(events).filter { it.startsWith("clear") || it.startsWith("append") })
        assertEquals(0, restarted.shuffles)
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `raw duplicate null unsupported order and count mismatches cannot falsely finalize`() = runTest {
        for (kind in 0..4) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.appendFailure = IOException("lost")
            f.api.loseAppend = true
            failure { f.generate() }
            val p = f.pending()
            f.api.rawOverride = when (kind) {
                0 -> p.uris.reversed()
                1 -> listOf(p.uris[0], p.uris[0], p.uris[0])
                2 -> p.uris.toMutableList<String?>().also { it[0] = null }
                3 -> listOf("spotify:episode:unsupported")
                else -> emptyList()
            }
            f.api.appendFailure = null
            val events = f.api.events.size
            assertEquals(p.uris, f.resume().uris)
            assertTrue(f.api.events.drop(events).contains("clear:deal1"))
        }
    }

    @Test fun `read failure or incomplete pagination preserves pending without writes`() = runTest {
        for (incomplete in listOf(false, true)) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.appendFailure = IOException("lost")
            failure { f.generate() }
            val before = f.disk.text
            if (incomplete) f.api.rawTransform = { it.copy(total = it.total + 1) }
            else f.api.rawFailure = SpotifyWebApiException(WebApiFailureCategory.CONNECTIVITY)
            val events = f.api.events.size
            failure { f.resume() }
            assertEquals(before, f.disk.text)
            assertFalse(f.api.events.drop(events).any { it.startsWith("clear") || it.startsWith("append") || it == "create" })
        }
    }

    @Test fun `complete receipts recover before delivered marker without auth lookup or remote read`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.disk.fail = { it.toString().contains("DELIVERED") }
        failure { f.generate() }
        val restarted = f.restart()
        assertTrue(restarted.pending().attempt!!.complete)
        val events = f.api.events.size
        f.api.contents.clear()
        restarted.token = null
        val result = restarted.resume()
        assertEquals(1, result.progress.completedDealCount)
        assertEquals(events, f.api.events.size)
        assertEquals(0, restarted.tokenCalls)
        assertTrue(failure { restarted.resume() } is OpportunityPendingException)
    }

    @Test fun `failed final local commit recovers delivered state without rewrite despite later edit`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.disk.fail = { root -> root.getJSONObject("sources").getJSONObject("source").isNull("pending") }
        failure { f.generate() }
        val restarted = f.restart()
        assertEquals(OpportunityPhase.DELIVERED, restarted.pending().phase)
        f.api.contents["deal1"] = mutableListOf(null, "spotify:track:edited")
        val events = f.api.events.size
        assertEquals(1, restarted.resume().progress.completedDealCount)
        assertEquals(events, f.api.events.size)
    }

    @Test fun `readback proven delivery with failed local commit re-verifies on restart before rebuilding same deal`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost acknowledgement")
        f.api.loseAppend = true
        failure { f.generate() }
        val prepared = f.pending().uris
        f.disk.fail = { root -> root.getJSONObject("sources").getJSONObject("source").isNull("pending") }
        failure { f.resume() }
        val restarted = f.restart()
        assertEquals(OpportunityPhase.DELIVERED, restarted.pending().phase)
        assertTrue(restarted.pending().exactContentsConfirmed)
        assertFalse(restarted.pending().attempt!!.complete)
        f.api.contents["deal1"] = mutableListOf("spotify:track:edited")
        f.api.appendFailure = null
        val events = f.api.events.size
        assertEquals(prepared, restarted.resume().uris)
        val resumed = f.api.events.drop(events)
        assertTrue(resumed.indexOf("raw") < resumed.indexOf("clear:deal1"))
        assertEquals(0, restarted.shuffles)
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `failed local commit after readback preserves proof when reverification fails or replacement canceled`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost acknowledgement")
        f.api.loseAppend = true
        failure { f.generate() }
        f.disk.fail = { root -> root.getJSONObject("sources").getJSONObject("source").isNull("pending") }
        failure { f.resume() }
        val restarted = f.restart()
        val before = restarted.disk.text
        f.api.rawFailure = IOException("readback unavailable")
        failure { restarted.resume() }
        assertEquals(before, restarted.disk.text)
        f.api.rawFailure = null
        f.api.contents.clear()
        assertNull(restarted.workflow().resumePendingSession("source", CreationNameProvider { null }))
        assertEquals(before, restarted.disk.text)
        f.api.appendFailure = null
        val result = restarted.resume()
        assertEquals("deal2", result.output.id)
        assertEquals(1, result.progress.completedDealCount)
    }

    @Test fun `clear ambiguity stays pending then verification precedes rebuilding`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.clearFailure = IOException("clear unknown")
        failure { f.generate() }
        assertEquals(0, f.api.appendCount)
        assertTrue(f.pending().attempt!!.receipts.isEmpty())
        f.api.clearFailure = null
        val events = f.api.events.size
        f.resume()
        val resumed = f.api.events.drop(events)
        assertTrue(resumed.indexOf("raw") < resumed.indexOf("clear:deal1"))
    }

    @Test fun `listing hit skips forbidden membership and reuses known pending ID`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        f.api.loseAppend = true
        failure { f.generate() }
        f.api.membershipFailure = SpotifyWebApiException(WebApiFailureCategory.PERMISSION, 403)
        val events = f.api.events.size
        assertEquals("deal1", f.resume().output.id)
        assertFalse(f.api.events.drop(events).contains("membership"))
        assertFalse(f.api.events.drop(events).contains("lookup"))
    }

    @Test fun `externally renamed pending playlist is reused without naming or renaming`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        f.api.loseAppend = true
        failure { f.generate() }
        f.api.names["deal1"] = "Externally changed name"
        val result = f.workflow().resumePendingSession("source", CreationNameProvider { throw AssertionError("No new creation name needed") })!!
        assertEquals("Externally changed name", result.output.name)
        assertEquals("Externally changed name", f.api.names["deal1"])
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `invalid returned acknowledgement stops following writes and does not deplete`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.clearFailure = com.royalshuffle.android.data.remote.SpotifyWebApiException(WebApiFailureCategory.INVALID_RESPONSE, 200)
        failure { f.generate() }
        assertEquals(0, f.api.appendCount)
        assertEquals(0, f.pending().candidate!!.completedDealCount)
        assertTrue(f.pending().attempt!!.receipts.isEmpty())
        f.api.clearFailure = null
        f.api.appendFailure = com.royalshuffle.android.data.remote.SpotifyWebApiException(WebApiFailureCategory.INVALID_RESPONSE, 201)
        failure { f.resume() }
        assertEquals(1, f.api.appendCount)
        assertEquals(0, f.pending().candidate!!.completedDealCount)
        assertEquals(1, f.pending().attempt!!.receipts.size)
    }

    @Test fun `listing miss membership ambiguity stops while confirmed unfollow replaces exact deal`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        failure { f.generate() }
        val p = f.pending()
        f.api.listed = false
        f.api.membershipFailure = SpotifyWebApiException(WebApiFailureCategory.PERMISSION, 403)
        val before = f.disk.text
        failure { f.resume() }
        assertEquals(before, f.disk.text)
        assertEquals(1, f.api.creationCalls)
        f.api.membershipFailure = null
        f.api.saved = false
        f.api.appendFailure = null
        val oldContents = f.api.contents.getValue("deal1").toList()
        val result = f.resume()
        assertEquals("deal2", result.output.id)
        assertEquals(p.uris, result.uris)
        assertEquals(oldContents, f.api.contents.getValue("deal1"))
        assertEquals(1, f.shuffles)
    }

    @Test fun `confirmed 404 replacement cancel or blank leaves original pending unchanged`() = runTest {
        for (name in listOf<String?>(null, " ")) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.api.appendFailure = IOException("lost")
            failure { f.generate() }
            f.api.contents.clear()
            val before = f.disk.text
            val result = runCatching { f.workflow().resumePendingSession("source", CreationNameProvider { name }) }
            if (name == null) assertNull(result.getOrThrow()) else assertTrue(result.isFailure)
            assertEquals(before, f.disk.text)
            assertEquals(1, f.api.creationCalls)
        }
    }

    @Test fun `raw 404 requires independent exact ID confirmation and never trusts earlier listing`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        failure { f.generate() }
        f.api.rawFailure = SpotifyWebApiException(WebApiFailureCategory.OTHER, 404)
        val before = f.disk.text
        assertTrue(failure { f.resume() } is OpportunityPendingException)
        assertEquals(before, f.disk.text)
        assertTrue(f.api.events.contains("lookup"))
        assertEquals(1, f.api.creationCalls)
    }

    @Test fun `three deals fresh IDs preserve earlier contents final short and natural rollover reload`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        val first = f.generate()
        val firstContents = f.api.contents.getValue(first.output.id).toList()
        f.api.sourceItems = OpportunityTestApi.items(1, 1, "changed")
        val second = f.generate()
        val third = f.generate()
        assertEquals(listOf(3, 3, 2), listOf(first, second, third).map { it.uris.size })
        assertEquals(3, setOf(first.output.id, second.output.id, third.output.id).size)
        assertEquals(firstContents, f.api.contents.getValue(first.output.id))
        assertEquals(1, f.api.sourceLoads)
        assertEquals(0, third.progress.remainingUniqueCount)
        val rollover = f.generate()
        assertEquals(listOf("spotify:track:changed1"), rollover.uris)
        assertEquals(2, f.api.sourceLoads)
        assertEquals(1, rollover.progress.completedDealCount)
        assertEquals(listOf("Source - Deal 1", "Source - Deal 2", "Source - Deal 3", "Source - Deal 1"), f.prompts)
        assertTrue(f.preferences.managed.isEmpty())
    }

    @Test fun `new rotation requires fresh confirmation and keeps old active until candidate commit`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.generate()
        val old = f.store.load().sources.getValue("source").active!!
        val token = f.workflow().inspectNewRotation("source")
        assertEquals(5, token.remainingUniqueCount)
        assertTrue(failure { f.workflow().startNewRotation(f.source, f.names) } is OpportunityPendingException)
        f.api.sourceItems = OpportunityTestApi.items(2, 1, "new")
        f.api.appendFailure = IOException("interrupted candidate")
        f.api.failAppendNumber = f.api.appendCount + 1
        failure { f.workflow().startNewRotation(f.source, f.names, token) }
        assertEquals(old.rotationId, f.store.load().sources.getValue("source").active!!.rotationId)
        assertNotEquals(old.rotationId, f.pending().candidate!!.rotationId)
        val before = f.disk.text
        assertTrue(failure { f.workflow().startNewRotation(f.source, f.names, token) } is OpportunityPendingException)
        assertEquals(before, f.disk.text)
        val candidateId = f.pending().candidate!!.rotationId
        f.api.appendFailure = null
        f.resume()
        assertEquals(candidateId, f.store.load().sources.getValue("source").active!!.rotationId)
    }

    @Test fun `explicit pending replacement leaves abandoned remote playlist untouched and naming cancel preserves pending`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        f.api.loseAppend = true
        failure { f.generate() }
        val old = f.pending()
        val contents = f.api.contents.getValue("deal1").toList()
        val token = f.workflow().inspectNewRotation("source")
        val before = f.disk.text
        assertNull(f.workflow().startNewRotation(f.source, CreationNameProvider { null }, token))
        assertEquals(before, f.disk.text)
        f.api.sourceItems = OpportunityTestApi.items(2, 1, "fresh")
        f.api.appendFailure = null
        val events = f.api.events.size
        val result = f.workflow().startNewRotation(f.source, f.names, token)!!
        assertEquals(listOf("spotify:track:fresh1", "spotify:track:fresh2"), result.uris)
        assertEquals(contents, f.api.contents.getValue(old.outputId!!))
        assertFalse(f.api.events.drop(events).any { it.startsWith("clear:deal1") || it.startsWith("append:deal1") })
    }

    @Test fun `explicit abandonment with exact operation token makes no remote calls`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.api.appendFailure = IOException("lost")
        failure { f.generate() }
        val p = f.pending()
        val events = f.api.events.size
        failure { f.workflow().abandonPendingSession("source", "wrong") }
        f.workflow().abandonPendingSession("source", p.operationId)
        assertEquals(events, f.api.events.size)
        assertTrue(f.api.contents.containsKey(p.outputId))
        assertTrue(f.store.load().sources.isEmpty())
    }

    @Test fun `ordinary Full timed and source collisions block Opportunity population`() = runTest {
        for (identity in listOf(OutputIdentity("ordinarySource", "full"), OutputIdentity("ordinarySource", "60"))) {
            val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
            f.preferences.bindings[identity] = "colliding"
            f.api.createId = "colliding"
            failure { f.generate() }
            assertFalse(f.api.events.any { it.startsWith("clear") || it.startsWith("append") })
            assertEquals(0, f.pending().candidate!!.completedDealCount)
        }
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.preferences.bindings[OutputIdentity("ordinarySource", "full")] = "ordinaryOutput"
        f.api.createId = "ordinarySource"
        failure { f.generate() }
        assertFalse(f.api.events.any { it.startsWith("clear") })
    }

    @Test fun `managed output cannot source and pending prevents generation even after settings change`() = runTest {
        val f = OpportunityTestEnvironment(StandardTestDispatcher(testScheduler))
        f.preferences.managed += "source"
        failure { f.generate() }
        assertTrue(f.api.events.isEmpty())
        f.preferences.managed.clear()
        f.api.appendFailure = IOException("lost")
        failure { f.generate() }
        val before = f.disk.text
        val events = f.api.events.size
        assertTrue(failure { f.generate(true) } is OpportunityPendingException)
        assertEquals(before, f.disk.text)
        assertEquals(events, f.api.events.size)
    }
}
