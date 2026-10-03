package com.royalshuffle.android.opportunity

import com.royalshuffle.android.output.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class OpportunityStoreTest {
    private class Disk : OpportunityPersistence {
        override val coordinationKey = UUID.randomUUID().toString()
        var text: String? = null
        var fail = false
        var writes = 0
        override fun read() = text
        override fun replace(document: String) {
            writes++
            if (fail) throw IOException("disk failure")
            text = document
        }
    }
    private val planner = OpportunityPlanner(OutputPlanner(OccurrenceShuffler { it }))
    private fun rotation(offset: Int = 0, count: Int = 3) = planner.candidate((1..count).map {
        OutputPlaylistItem("spotify:track:t${it + offset}", durationMs = 2_000_000, primaryArtistId = "artist")
    })
    private class Fixture(val disk: Disk = Disk()) {
        val store = OpportunityStore(disk)
    }
    private fun stage(f: Fixture, candidate: OpportunityRotation? = null): OpportunityPending {
        val active = f.store.load().sources["source"]?.active
        return f.store.stage(planner.prepare("source", candidate ?: active!!), "Source - Deal 1", candidate)
    }
    private fun output(f: Fixture, p: OpportunityPending) {
        f.store.markCreating("source", p.operationId)
        f.store.recordCreatedOutput("source", p.operationId, "output")
    }
    private fun exact(f: Fixture, p: OpportunityPending) {
        output(f, p)
        f.store.confirmExactContents("source", p.operationId, "output", p.uris)
    }

    @Test fun `new rotation staging and pending reload preserve exact records without depletion`() {
        val f = Fixture()
        val rotation = rotation()
        f.store.beginRotation("source", rotation)
        val p = stage(f)
        val loaded = OpportunityStore(f.disk).load().sources.getValue("source")
        assertEquals(rotation.undealt, loaded.active!!.undealt)
        assertEquals(0, loaded.active.completedDealCount)
        val pending = loaded.pending!!
        assertEquals(p.uris, pending.uris)
        assertEquals(p.operationId, pending.operationId)
        assertEquals("Source - Deal 1", pending.creationName)
        assertThrows(UnsupportedOperationException::class.java) { (pending.uris as MutableList).reverse() }
    }

    @Test fun `commit shrinks exact membership counts deals and discards completed output identity`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        exact(f, p)
        val committed = f.store.finalize("source", p.operationId)
        assertEquals(listOf("spotify:track:t3"), committed.undealt.map { it.uri })
        assertEquals(3, committed.originalUniqueCount)
        assertEquals(1, committed.completedDealCount)
        assertNull(f.store.load().sources.getValue("source").pending)
        assertFalse(f.disk.text!!.contains("\"output\""))
        assertThrows(OpportunityStateException::class.java) { f.store.finalize("source", p.operationId) }
        val final = stage(f)
        exact(f, final)
        assertTrue(f.store.finalize("source", final.operationId).exhausted)
        assertEquals(2, f.store.load().sources.getValue("source").active!!.completedDealCount)
    }

    @Test fun `stale preparation and duplicate staging rejected`() {
        val f = Fixture()
        val rotation = rotation()
        f.store.beginRotation("source", rotation)
        val stale = planner.prepare("source", rotation)
        val p = stage(f)
        assertThrows(OpportunityStateException::class.java) { stage(f) }
        exact(f, p)
        f.store.finalize("source", p.operationId)
        assertThrows(OpportunityStateException::class.java) { f.store.stage(stale, "name") }
    }

    @Test fun `candidate stays separate and installs only after successful commit`() {
        val f = Fixture()
        val old = rotation()
        f.store.beginRotation("source", old)
        val candidate = rotation(10)
        val p = stage(f, candidate)
        assertEquals(old.rotationId, f.store.load().sources.getValue("source").active!!.rotationId)
        assertEquals(candidate.rotationId, f.store.load().sources.getValue("source").pending!!.candidate!!.rotationId)
        assertThrows(OpportunityStateException::class.java) { f.store.finalize("source", p.operationId) }
        exact(f, p)
        assertEquals(candidate.rotationId, f.store.finalize("source", p.operationId).rotationId)
        assertEquals(listOf("spotify:track:t13"), f.store.load().sources.getValue("source").active!!.undealt.map { it.uri })
    }

    @Test fun `initial candidate can reload commit or be explicitly abandoned`() {
        val f = Fixture()
        val p = stage(f, rotation())
        assertNull(f.store.load().sources.getValue("source").active)
        exact(f, p)
        assertEquals(1, f.store.finalize("source", p.operationId).completedDealCount)
        val fresh = Fixture()
        val abandoned = stage(fresh, rotation())
        fresh.store.abandonPending("source", abandoned.operationId)
        assertTrue(fresh.store.load().sources.isEmpty())
    }

    @Test fun `abandonment requires exact operation and preserves old active`() {
        val f = Fixture()
        val old = rotation()
        f.store.beginRotation("source", old)
        val p = stage(f, rotation(10))
        output(f, p)
        val before = f.disk.text
        assertThrows(OpportunityStateException::class.java) { f.store.abandonPending("source", UUID.randomUUID().toString()) }
        assertEquals(before, f.disk.text)
        f.store.abandonPending("source", p.operationId)
        assertEquals(old.undealt, f.store.load().sources.getValue("source").active!!.undealt)
        assertNull(f.store.load().sources.getValue("source").pending)
    }

    @Test fun `confirmed pending replacement is atomic and cancellation or stale token cannot replace it`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val old = stage(f)
        val candidate = rotation(10)
        val deal = planner.prepare("source", candidate)
        val before = f.disk.text
        assertThrows(OpportunityStateException::class.java) { f.store.stage(deal, "name", candidate, "wrong") }
        assertThrows(OpportunityStateException::class.java) { f.store.stage(deal, " ", candidate, old.operationId) }
        assertEquals(before, f.disk.text)
        val replacement = f.store.stage(deal, "name", candidate, old.operationId)
        assertNotEquals(old.operationId, replacement.operationId)
        assertEquals(0, f.store.load().sources.getValue("source").active!!.completedDealCount)
    }

    @Test fun `no commit before delivery and invalid phase transitions do not write`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        val before = f.disk.text
        assertThrows(OpportunityStateException::class.java) { f.store.finalize("source", p.operationId) }
        assertThrows(OpportunityStateException::class.java) { f.store.recordCreatedOutput("source", p.operationId, "output") }
        assertThrows(OpportunityStateException::class.java) { f.store.beginDeliveryAttempt("source", p.operationId) }
        assertEquals(before, f.disk.text)
        f.store.markCreating("source", p.operationId)
        f.store.recordCreationRejected("source", p.operationId)
        assertEquals(OpportunityPhase.STAGED, f.store.load().sources.getValue("source").pending!!.phase)
    }

    @Test fun `multi batch receipts reload bind attempt and allow acknowledged commit`() {
        val f = Fixture()
        val r = planner.candidate((1..101).map { OutputPlaylistItem("spotify:track:t$it", durationMs = 1, primaryArtistId = "a") })
        f.store.beginRotation("source", r)
        val p = stage(f)
        output(f, p)
        val attempt = f.store.beginDeliveryAttempt("source", p.operationId).attempt!!
        assertThrows(OpportunityStateException::class.java) { f.store.markAcknowledged("source", p.operationId) }
        assertThrows(OpportunityStateException::class.java) { f.store.appendReceipt("source", p.operationId, "wrong",
            OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, 200, "snapshot")) }
        f.store.appendReceipt("source", p.operationId, attempt.attemptId, OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, 200, "s0"))
        f.store.appendReceipt("source", p.operationId, attempt.attemptId, OpportunityReceipt(1, OpportunityWriteMethod.APPEND, 0, 100, 201, "s1"))
        val reloaded = OpportunityStore(f.disk)
        assertEquals(2, reloaded.load().sources.getValue("source").pending!!.attempt!!.receipts.size)
        reloaded.appendReceipt("source", p.operationId, attempt.attemptId, OpportunityReceipt(2, OpportunityWriteMethod.APPEND, 100, 101, 201, "s2"))
        reloaded.markAcknowledged("source", p.operationId)
        assertTrue(reloaded.finalize("source", p.operationId).exhausted)
    }

    @Test fun `incomplete attempt rebuild starts new receipts and rejects old attempt`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        output(f, p)
        val first = f.store.beginDeliveryAttempt("source", p.operationId).attempt!!
        f.store.appendReceipt("source", p.operationId, first.attemptId, OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, 200, "s"))
        val rebuilt = f.store.beginDeliveryAttempt("source", p.operationId)
        assertNotEquals(first.attemptId, rebuilt.attempt!!.attemptId)
        assertTrue(rebuilt.attempt.receipts.isEmpty())
        assertEquals(p.uris, rebuilt.uris)
        assertThrows(OpportunityStateException::class.java) { f.store.appendReceipt("source", p.operationId, first.attemptId,
            OpportunityReceipt(1, OpportunityWriteMethod.APPEND, 0, 2, 201, "s")) }
    }

    @Test fun `exact confirmation rejects order duplicates null slots count and foreign output`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        output(f, p)
        for (actual in listOf(p.uris.reversed(), listOf(p.uris[0], p.uris[0]), listOf(null), emptyList())) {
            assertThrows(OpportunityStateException::class.java) { f.store.confirmExactContents("source", p.operationId, "output", actual) }
        }
        assertThrows(OpportunityStateException::class.java) { f.store.confirmExactContents("source", p.operationId, "other", p.uris) }
        f.store.confirmExactContents("source", p.operationId, "output", p.uris)
        assertEquals(1, f.store.finalize("source", p.operationId).completedDealCount)
    }

    @Test fun `malformed version duplicate keys trailing data and wrong numeric types preserved`() {
        val f = Fixture()
        for (text in listOf("broken", "{\"version\":2,\"sources\":{}}", "{\"version\":1,\"version\":1,\"sources\":{}}",
            "{\"version\":1,\"sources\":{}} trailing", "{\"version\":\"1\",\"sources\":{}}", "{\"version\":1.0,\"sources\":{}}",
            "{\"version\":01,\"sources\":{}}", "{\"version\":1,\"sources\":{},\"extra\":true}")) {
            f.disk.text = text
            assertThrows(OpportunityStateException::class.java) { f.store.load() }
            assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", rotation()) }
            assertEquals(text, f.disk.text)
            assertEquals(0, f.disk.writes)
        }
    }

    @Test fun `tampered expected state membership output and attempt payload rejected`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        output(f, p)
        f.store.beginDeliveryAttempt("source", p.operationId)
        val valid = f.disk.text!!
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("expected_revision", 1) }, { it.put("expected_completed_count", 1) },
            { it.put("expected_rotation_id", UUID.randomUUID().toString()) },
            { it.put("expected_snapshot", "altered") },
            { it.put("uris", org.json.JSONArray(listOf("spotify:track:foreign"))) },
            { it.put("output_id", "source") },
            { it.getJSONObject("attempt").put("uris", org.json.JSONArray(p.uris.reversed())) },
            { it.put("phase", "DELIVERED") })
        edits.forEach { edit ->
            val root = JSONObject(valid)
            edit(root.getJSONObject("sources").getJSONObject("source").getJSONObject("pending"))
            f.disk.text = root.toString()
            assertThrows(OpportunityStateException::class.java) { f.store.load() }
        }
    }

    @Test fun `failed finalization preserves disk candidate and active and poisons all store wrappers`() {
        val f = Fixture()
        val old = rotation()
        f.store.beginRotation("source", old)
        val p = stage(f, rotation(10))
        exact(f, p)
        val before = f.disk.text
        f.disk.fail = true
        assertThrows(OpportunityStateException::class.java) { f.store.finalize("source", p.operationId) }
        assertEquals(before, f.disk.text)
        assertThrows(OpportunityStateException::class.java) { f.store.load() }
        assertThrows(OpportunityStateException::class.java) { OpportunityStore(f.disk).load() }
        // A new process has a fresh guard; emulate by presenting the same durable bytes under a fresh backend identity.
        val restarted = Fixture(Disk().apply { text = before })
        assertEquals(old.rotationId, restarted.store.load().sources.getValue("source").active!!.rotationId)
        assertEquals(p.candidate!!.rotationId, restarted.store.finalize("source", p.operationId).rotationId)
    }

    @Test fun `failed initial write stops transitions and preserves missing document`() {
        val f = Fixture()
        f.disk.fail = true
        assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", rotation()) }
        assertNull(f.disk.text)
        f.disk.fail = false
        assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", rotation()) }
        assertEquals(1, f.disk.writes)
    }

    @Test fun `confirmed missing replacement changes only delivery identity and name`() {
        val f = Fixture()
        val p = stage(f, rotation())
        output(f, p)
        f.store.beginDeliveryAttempt("source", p.operationId)
        assertThrows(OpportunityStateException::class.java) { f.store.stageConfirmedMissingOutput("source", p.operationId, "other", "Replacement") }
        val replacement = f.store.stageConfirmedMissingOutput("source", p.operationId, "output", "Replacement")
        assertEquals(p.uris, replacement.uris)
        assertEquals(p.candidate!!.undealt, replacement.candidate!!.undealt)
        assertEquals(p.operationId, replacement.operationId)
        assertEquals("Replacement", replacement.creationName)
        assertNull(replacement.outputId)
        assertNull(replacement.attempt)
        assertEquals(OpportunityPhase.STAGED, replacement.phase)
    }

    @Test fun `malformed receipts reject wrong method status range sequence and empty snapshot`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val p = stage(f)
        output(f, p)
        val attempt = f.store.beginDeliveryAttempt("source", p.operationId).attempt!!
        val before = f.disk.text
        val good = OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, 200, "snapshot")
        listOf(good.copy(index = 1), good.copy(method = OpportunityWriteMethod.APPEND), good.copy(start = 1),
            good.copy(end = 1), good.copy(status = 201), good.copy(snapshotId = " ")).forEach { bad ->
            assertThrows(OpportunityStateException::class.java) { f.store.appendReceipt("source", p.operationId, attempt.attemptId, bad) }
            assertEquals(before, f.disk.text)
        }
    }

    @Test fun `failed phase or receipt write leaves durable intent and prevents later transitions`() {
        for (receiptFailure in listOf(false, true)) {
            val f = Fixture()
            val p = stage(f, rotation())
            val attempt = if (receiptFailure) {
                output(f, p)
                f.store.beginDeliveryAttempt("source", p.operationId).attempt!!
            } else null
            val before = f.disk.text
            f.disk.fail = true
            assertThrows(OpportunityStateException::class.java) {
                if (receiptFailure) f.store.appendReceipt("source", p.operationId, attempt!!.attemptId,
                    OpportunityReceipt(0, OpportunityWriteMethod.CLEAR, 0, 0, 200, "s"))
                else f.store.markCreating("source", p.operationId)
            }
            assertEquals(before, f.disk.text)
            f.disk.fail = false
            assertThrows(OpportunityStateException::class.java) { f.store.abandonPending("source", p.operationId) }
        }
    }

    @Test fun `changed frozen metadata fails expected snapshot validation`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        stage(f)
        val root = JSONObject(f.disk.text!!)
        root.getJSONObject("sources").getJSONObject("source").getJSONObject("active")
            .getJSONArray("undealt").getJSONObject(0).put("duration_ms", 1)
        f.disk.text = root.toString()
        assertThrows(OpportunityStateException::class.java) { f.store.load() }
    }

    @Test fun `malformed rotation duplicate pool progress and record metadata rejected`() {
        val f = Fixture()
        f.store.beginRotation("source", rotation())
        val valid = f.disk.text!!
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("rotation_id", "invalid") }, { it.put("original_count", 0) }, { it.put("completed_count", 1) },
            { it.getJSONArray("undealt").put(it.getJSONArray("undealt").getJSONObject(0)) },
            { it.getJSONArray("undealt").getJSONObject(0).put("duration_ms", 0) },
            { it.getJSONArray("undealt").getJSONObject(0).put("duration_ms", "2000000") },
            { it.getJSONArray("undealt").getJSONObject(0).put("artist_id", " ") })
        edits.forEach { edit ->
            val root = JSONObject(valid)
            edit(root.getJSONObject("sources").getJSONObject("source").getJSONObject("active"))
            f.disk.text = root.toString()
            assertThrows(OpportunityStateException::class.java) { f.store.load() }
        }
    }

    @Test fun `altered duplicate or foreign prepared membership cannot stage`() {
        val f = Fixture()
        val r = rotation()
        f.store.beginRotation("source", r)
        val original = r.undealt.first()
        for (tracks in listOf(emptyList(), listOf(original, original), listOf(original.copy(durationMs = 1)),
            listOf(original.copy(uri = "spotify:track:foreign")))) {
            assertThrows(OpportunityStateException::class.java) {
                f.store.stage(PreparedOpportunityDeal("source", r, tracks), "name")
            }
        }
        assertNull(f.store.load().sources.getValue("source").pending)
    }

    @Test fun `begin rotation cannot overwrite unfinished active pending or reuse exhausted identity`() {
        val f = Fixture()
        val r = rotation(count = 1)
        f.store.beginRotation("source", r)
        assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", rotation(10)) }
        val p = stage(f)
        assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", rotation(10)) }
        exact(f, p)
        f.store.finalize("source", p.operationId)
        assertThrows(OpportunityStateException::class.java) { f.store.beginRotation("source", r) }
        val replacement = rotation(10)
        f.store.beginRotation("source", replacement)
        assertEquals(replacement.rotationId, f.store.load().sources.getValue("source").active!!.rotationId)
    }

    @Test fun `source and other pending output collisions fail before saving output identity`() {
        val f = Fixture()
        val p = stage(f, rotation())
        f.store.markCreating("source", p.operationId)
        val before = f.disk.text
        assertThrows(OpportunityStateException::class.java) { f.store.recordCreatedOutput("source", p.operationId, "source") }
        assertEquals(before, f.disk.text)
        f.store.recordCreatedOutput("source", p.operationId, "output")
        val candidate = rotation(10)
        val other = f.store.stage(planner.prepare("other", candidate), "name", candidate)
        f.store.markCreating("other", other.operationId)
        assertThrows(OpportunityStateException::class.java) { f.store.recordCreatedOutput("other", other.operationId, "output") }
        assertNull(f.store.load().sources.getValue("other").pending!!.outputId)
    }
}
