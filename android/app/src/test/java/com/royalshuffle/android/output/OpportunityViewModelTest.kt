package com.royalshuffle.android.output

import com.royalshuffle.android.auth.AccessTokenProvider
import com.royalshuffle.android.domain.model.Playlist
import com.royalshuffle.android.diagnostics.DiagnosticEvent
import com.royalshuffle.android.diagnostics.DiagnosticLogger
import com.royalshuffle.android.opportunity.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OpportunityViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var env: OpportunityTestEnvironment
    private val events = mutableListOf<DiagnosticEvent>()
    @Before fun setup() { Dispatchers.setMain(dispatcher); env = OpportunityTestEnvironment(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun model(environment: OpportunityTestEnvironment = env,
        storage: OutputSettingsStorage = MemoryOutputSettingsStorage(), enabled: Boolean = true,
        sourceId: String? = environment.source.id): OutputViewModel {
        val ordinary = CreateOutputPlaylist(AccessTokenProvider { environment.token }, environment.api,
            environment.preferences, OccurrenceShuffler { it }, registry = environment.preferences,
            coordinator = environment.coordinator, identityProtection = OpportunityIdentityProtection(environment.store))
        return OutputViewModel(ordinary, storage, opportunityWorkflow = environment.workflow(),
            initialSourceId = sourceId, diagnostics = DiagnosticLogger { events += it }).also {
            if (enabled) it.setOpportunityEnabled(true)
        }
    }
    private fun TestScope.submitName(vm: OutputViewModel, name: String? = null) {
        runCurrent()
        val request = vm.nameRequest.value!!
        vm.confirmName(request.requestId, name ?: request.defaultName)
        advanceUntilIdle()
    }
    private suspend fun preparePending() {
        env.api.appendFailure = IOException("interrupted")
        env.api.failAppendNumber = env.api.appendCount + 1
        runCatching { env.generate() }
        assertNotNull(env.pending())
    }

    @Test fun `missing listed source restores actionable pending and resumes saved identity without redraw`() = runTest(dispatcher) {
        preparePending()
        val intended = env.pending().uris.toList()
        val restarted = env.restart()
        val repository = com.royalshuffle.android.playlist.PlaylistRepository(
            AccessTokenProvider { "token" }, object : com.royalshuffle.android.playlist.PlaylistApi {
                override suspend fun getPlaylistsPage(url: String, accessToken: String) =
                    com.royalshuffle.android.playlist.PlaylistPage(listOf(Playlist("other", "Other")), null)
            }, restarted.preferences, identityProtection = OpportunityIdentityProtection(restarted.store))
        val listed = repository.loadEligiblePlaylists()
        assertFalse(listed.playlists.any { it.id == "source" })
        val before = restarted.disk.text
        val vm = model(restarted, sourceId = listed.selectedPlaylistId)
        advanceUntilIdle()
        assertEquals(before, restarted.disk.text)
        assertNull(vm.opportunityState.value.sourceId)
        val session = vm.opportunityState.value.pendingSessions.single()
        assertEquals("source", session.sourceId)
        // A different current selection must never redirect recovery.
        vm.selectOpportunitySource("other")
        advanceUntilIdle()
        restarted.api.sourceItems = emptyList()
        restarted.api.appendFailure = null
        val loads = restarted.api.sourceLoads
        vm.resumePendingSession(session)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is OutputUiState.Success)
        assertEquals(intended, restarted.api.contents.getValue("deal1"))
        assertEquals(loads, restarted.api.sourceLoads)
        assertEquals(0, restarted.shuffles)
        assertNull(restarted.store.load().sources.getValue("source").pending)
        assertFalse(restarted.store.load().sources.containsKey("other"))
        assertEquals("other", vm.opportunityState.value.sourceId)
    }

    @Test fun `standalone confirmed abandonment needs neither selectable nor usable source nor authentication`() = runTest(dispatcher) {
        for (selected in listOf<String?>(null, "source")) {
            preparePending()
            env.api.sourceItems = emptyList()
            val vm = model(sourceId = selected)
            advanceUntilIdle()
            val session = vm.opportunityState.value.pendingSessions.single()
            val before = env.disk.text
            val remote = env.api.events.toList()
            val contents = env.api.contents.mapValues { it.value.toList() }
            env.token = null
            vm.abandonPendingSession(session)
            runCurrent()
            val request = vm.confirmationRequest.value!!
            assertTrue(request.abandonOnly)
            assertEquals("Abandon Pending Session?", request.title)
            assertEquals(session.pending.operationId, request.confirmation.pendingOperationId)
            assertEquals(before, env.disk.text)
            vm.confirmNewRotation(request.requestId)
            advanceUntilIdle()
            assertTrue(vm.opportunityState.value.pendingSessions.isEmpty())
            assertEquals(remote, env.api.events)
            assertEquals(contents, env.api.contents.mapValues { it.value.toList() })
            assertNull(vm.nameRequest.value)
            env.token = "token"
            env.api.sourceItems = OpportunityTestApi.items(8)
        }
    }

    @Test fun `cancel standalone abandonment preserves exact durable pending`() = runTest(dispatcher) {
        preparePending()
        val vm = model(sourceId = null)
        advanceUntilIdle()
        val before = env.disk.text
        val remote = env.api.events.toList()
        vm.abandonPendingSession(vm.opportunityState.value.pendingSessions.single())
        runCurrent()
        vm.cancelNewRotation(vm.confirmationRequest.value!!.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(remote, env.api.events)
        assertEquals(1, vm.opportunityState.value.pendingSessions.size)
    }

    @Test fun `stale standalone abandonment confirmation cannot delete replacement pending`() = runTest(dispatcher) {
        preparePending()
        val vm = model(sourceId = null)
        advanceUntilIdle()
        val session = vm.opportunityState.value.pendingSessions.single()
        vm.abandonPendingSession(session)
        runCurrent()
        val request = vm.confirmationRequest.value!!
        env.workflow().abandonPendingSession(session.sourceId, session.pending.operationId)
        preparePending()
        val before = env.disk.text
        val remote = env.api.events.toList()
        vm.confirmNewRotation(request.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(remote, env.api.events)
        assertTrue(vm.uiState.value is OutputUiState.Error)
        assertNotEquals(session.pending.operationId, vm.opportunityState.value.pendingSessions.single().pending.operationId)
    }

    @Test fun `stale pending resume action cannot recover a replacement operation`() = runTest(dispatcher) {
        preparePending()
        val vm = model(sourceId = null)
        advanceUntilIdle()
        val session = vm.opportunityState.value.pendingSessions.single()
        env.workflow().abandonPendingSession(session.sourceId, session.pending.operationId)
        preparePending()
        val before = env.disk.text
        val remote = env.api.events.toList()
        vm.resumePendingSession(session)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(remote, env.api.events)
        assertTrue(vm.uiState.value is OutputUiState.Error)
    }

    @Test fun `standalone abandonment leaves active rotation and new rotation still requires usable source`() = runTest(dispatcher) {
        env.generate()
        val active = env.store.load().sources.getValue("source").active!!
        preparePending()
        val vm = model()
        advanceUntilIdle()
        vm.abandonPendingSession(vm.opportunityState.value.pendingSessions.single())
        runCurrent()
        vm.confirmNewRotation(vm.confirmationRequest.value!!.requestId)
        advanceUntilIdle()
        assertEquals(active.rotationId, env.store.load().sources.getValue("source").active!!.rotationId)
        val before = env.disk.text
        val creations = env.api.creationCalls
        env.api.sourceItems = emptyList()
        vm.startNewRotation(env.source)
        runCurrent()
        assertFalse(vm.confirmationRequest.value!!.abandonOnly)
        vm.confirmNewRotation(vm.confirmationRequest.value!!.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(creations, env.api.creationCalls)
        assertNull(vm.nameRequest.value)
        assertTrue(vm.uiState.value is OutputUiState.Error)
    }

    @Test fun `fresh mode is off and local restoration does not contact Spotify`() = runTest(dispatcher) {
        val vm = model(enabled = false)
        advanceUntilIdle()
        assertFalse(vm.settings.value.opportunityEnabled)
        assertEquals("Generate Next Session", vm.opportunityState.value.primaryActionText)
        assertTrue(vm.opportunityState.value.canSubmit)
        assertTrue(env.api.events.isEmpty())
        assertEquals(0, env.tokenCalls)
    }

    @Test fun `mode preserves ordinary Custom draft and allows independent artist setting`() = runTest(dispatcher) {
        val storage = MemoryOutputSettingsStorage()
        storage.save(OutputSettings(SessionLengthMode.CUSTOM, "91"))
        val vm = model(storage = storage)
        vm.setSessionLength(SessionLengthMode.FULL)
        vm.setCustomMinutes("5")
        vm.setArtistSeparation(true)
        assertEquals(SessionLengthMode.CUSTOM, vm.settings.value.mode)
        assertEquals("91", vm.settings.value.customMinutes)
        assertTrue(vm.settings.value.artistSeparation)
        vm.setOpportunityEnabled(false)
        assertEquals(OutputSettings(SessionLengthMode.CUSTOM, "91", true), vm.settings.value)
        val reconstructed = model(storage = storage, enabled = false)
        advanceUntilIdle()
        assertEquals(vm.settings.value, reconstructed.settings.value)
    }

    @Test fun `enabled mode persists across reconstruction`() = runTest(dispatcher) {
        val storage = MemoryOutputSettingsStorage()
        model(storage = storage)
        val fresh = model(storage = storage, enabled = false)
        advanceUntilIdle()
        assertTrue(fresh.settings.value.opportunityEnabled)
    }

    @Test fun `Opportunity ignores invalid ordinary Custom and delivers fixed sixty minutes`() = runTest(dispatcher) {
        val storage = MemoryOutputSettingsStorage()
        storage.save(OutputSettings(SessionLengthMode.CUSTOM, "bad"))
        val vm = model(storage = storage)
        vm.create(env.source)
        submitName(vm)
        assertEquals(3, (vm.uiState.value as OutputUiState.Success).itemCount)
        assertEquals("bad", vm.settings.value.customMinutes)
        assertTrue(env.preferences.bindings.isEmpty())
    }

    @Test fun `every new deal names a fresh playlist and forwards edited name`() = runTest(dispatcher) {
        val vm = model()
        vm.create(env.source)
        runCurrent()
        assertEquals("Source - Deal 1", vm.nameRequest.value!!.defaultName)
        submitName(vm, " My Deal ")
        vm.create(env.source)
        runCurrent()
        assertEquals("Source - Deal 2", vm.nameRequest.value!!.defaultName)
        submitName(vm)
        assertEquals(mapOf("deal1" to "My Deal", "deal2" to "Source - Deal 2"), env.api.names)
        assertEquals(2, env.api.creationCalls)
        assertEquals(3, env.api.contents.getValue("deal1").size)
        assertTrue(env.preferences.bindings.isEmpty())
    }

    @Test fun `blank name and cancel have no remote writes and no staged rotation`() = runTest(dispatcher) {
        val vm = model()
        vm.create(env.source)
        runCurrent()
        val request = vm.nameRequest.value!!
        vm.confirmName(request.requestId, "  ")
        runCurrent()
        assertNotNull(vm.nameRequest.value!!.errorMessage)
        assertEquals(0, env.api.creationCalls)
        vm.cancelName(request.requestId)
        advanceUntilIdle()
        assertEquals(OutputUiState.Idle, vm.uiState.value)
        assertFalse(vm.isRunning.value)
        assertTrue(env.store.load().sources.isEmpty())
        assertEquals(listOf("source"), env.api.events)
    }

    @Test fun `immediate gate prevents duplicate submissions and conflicting new rotation during naming`() = runTest(dispatcher) {
        val vm = model()
        vm.create(env.source)
        vm.create(env.source)
        vm.startNewRotation(env.source)
        vm.setOpportunityEnabled(false)
        assertTrue(vm.isRunning.value)
        assertTrue(vm.settings.value.opportunityEnabled)
        runCurrent()
        assertEquals(1, env.api.sourceLoads)
        submitName(vm)
        assertEquals(1, env.api.creationCalls)
    }

    @Test fun `pending projection shows Resume and primary create resumes without loading or redrawing`() = runTest(dispatcher) {
        preparePending()
        val uris = env.pending().uris.toList()
        val loads = env.api.sourceLoads
        val shuffles = env.shuffles
        env.api.appendFailure = null
        val vm = model()
        advanceUntilIdle()
        assertEquals("Resume Pending Session", vm.opportunityState.value.primaryActionText)
        assertTrue(vm.opportunityState.value.status.contains("Pending session"))
        vm.create(env.source)
        advanceUntilIdle()
        assertNull(vm.nameRequest.value)
        assertEquals(uris, env.api.contents.getValue("deal1"))
        assertEquals(loads, env.api.sourceLoads)
        assertEquals(shuffles, env.shuffles)
        assertEquals(1, env.api.creationCalls)
        assertEquals("Generate Next Session", vm.opportunityState.value.primaryActionText)
    }

    @Test fun `new rotation cancel preserves unfinished rotation and shows remaining count`() = runTest(dispatcher) {
        env.generate()
        val before = env.disk.text
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        val request = vm.confirmationRequest.value!!
        assertEquals(5, request.confirmation.remainingUniqueCount)
        assertTrue(request.message.contains("5 unique"))
        vm.cancelNewRotation(request.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(1, env.api.sourceLoads)
        assertFalse(vm.isRunning.value)
    }

    @Test fun `confirmed unfinished rotation starts fresh only after naming`() = runTest(dispatcher) {
        env.generate()
        val previous = env.disk.text
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        vm.confirmNewRotation(vm.confirmationRequest.value!!.requestId)
        runCurrent()
        assertEquals("Source - Deal 1", vm.nameRequest.value!!.defaultName)
        assertEquals(previous, env.disk.text)
        submitName(vm)
        assertEquals(1, vm.opportunityState.value.savedSource!!.active!!.completedDealCount)
        assertEquals(2, env.api.creationCalls)
    }

    @Test fun `pending abandonment passes exact token and leaves created playlist untouched`() = runTest(dispatcher) {
        preparePending()
        val pendingId = env.pending().operationId
        val oldContents = env.api.contents.getValue("deal1").toList()
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        val request = vm.confirmationRequest.value!!
        assertEquals(pendingId, request.confirmation.pendingOperationId)
        assertTrue(request.message.contains("left untouched"))
        env.api.appendFailure = null
        vm.confirmNewRotation(request.requestId)
        submitName(vm)
        assertEquals(oldContents, env.api.contents.getValue("deal1"))
        assertTrue(env.api.contents.containsKey("deal2"))
        assertNull(env.store.load().sources.getValue("source").pending)
    }

    @Test fun `stale exact confirmation rejects changed pending without remote write`() = runTest(dispatcher) {
        preparePending()
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        val request = vm.confirmationRequest.value!!
        env.api.appendFailure = null
        env.resume()
        val before = env.disk.text
        val creations = env.api.creationCalls
        vm.confirmNewRotation(request.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(creations, env.api.creationCalls)
        assertTrue((vm.uiState.value as OutputUiState.Error).message.contains("confirm"))
        assertNull(vm.nameRequest.value)
    }

    @Test fun `duplicate new rotation while confirmation is open has one exact request`() = runTest(dispatcher) {
        env.generate()
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        val request = vm.confirmationRequest.value
        vm.startNewRotation(env.source)
        vm.create(env.source)
        runCurrent()
        assertEquals(request, vm.confirmationRequest.value)
        vm.cancelNewRotation(request!!.requestId)
        advanceUntilIdle()
        assertEquals(1, env.api.creationCalls)
    }

    @Test fun `active progress and exhaustion restore solely from durable state`() = runTest(dispatcher) {
        repeat(3) { env.generate() }
        val restarted = env.restart()
        val before = env.api.events.toList()
        val vm = model(restarted)
        advanceUntilIdle()
        assertEquals(before, env.api.events)
        assertTrue(vm.opportunityState.value.status.contains("3 Deals completed"))
        assertTrue(vm.opportunityState.value.status.contains("0 unique"))
        assertTrue(vm.opportunityState.value.status.contains("Rotation complete"))
        vm.startNewRotation(restarted.source)
        runCurrent()
        assertNull(vm.confirmationRequest.value)
        submitName(vm)
        assertEquals(1, vm.opportunityState.value.savedSource!!.active!!.completedDealCount)
    }

    @Test fun `reconstructed pending retains exact order despite changed source and artist settings`() = runTest(dispatcher) {
        preparePending()
        val intended = env.pending().uris.toList()
        val restarted = env.restart()
        restarted.api.sourceItems = OpportunityTestApi.items(20, prefix = "changed")
        restarted.api.appendFailure = null
        val before = env.api.events.toList()
        val vm = model(restarted)
        vm.setArtistSeparation(true)
        advanceUntilIdle()
        assertEquals(before, env.api.events)
        assertEquals(intended, vm.opportunityState.value.savedSource!!.pending!!.uris)
        vm.create(restarted.source)
        advanceUntilIdle()
        assertEquals(intended, env.api.contents.getValue("deal1"))
        assertEquals(0, restarted.shuffles)
        assertEquals(0, restarted.tokenCalls - 1)
    }

    @Test fun `restoration without selection still announces saved pending sessions`() = runTest(dispatcher) {
        preparePending()
        val vm = model(env.restart(), sourceId = null)
        advanceUntilIdle()
        assertEquals(1, vm.opportunityState.value.pendingSourceCount)
        assertFalse(vm.opportunityState.value.canSubmit)
        assertTrue(vm.opportunityState.value.status.contains("saved pending session controls"))
        vm.selectOpportunitySource("source")
        advanceUntilIdle()
        assertEquals("Resume Pending Session", vm.opportunityState.value.primaryActionText)
    }

    @Test fun `disabling mode preserves durable progress and ordinary bindings`() = runTest(dispatcher) {
        env.generate()
        val identity = OutputIdentity.from("source", OutputOptions())
        env.preferences.bindings[identity] = "ordinary"
        val before = env.disk.text
        val vm = model()
        vm.setOpportunityEnabled(false)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals("ordinary", env.preferences.bindings[identity])
        assertEquals(1, vm.opportunityState.value.savedSource!!.active!!.completedDealCount)
    }

    @Test fun `auth invalidation during append preserves pending and reconnect resumes`() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        env.api.beforeMutation = { if (it.startsWith("append")) hold.await() }
        val vm = model()
        vm.create(env.source)
        runCurrent()
        vm.confirmName(vm.nameRequest.value!!.requestId, "Session")
        runCurrent()
        val intended = env.pending().uris.toList()
        vm.clearForSessionInvalidation()
        advanceUntilIdle()
        assertFalse(vm.isRunning.value)
        assertEquals(intended, vm.opportunityState.value.savedSource!!.pending!!.uris)
        env.api.beforeMutation = {}
        vm.create(env.source)
        advanceUntilIdle()
        assertEquals(intended, env.api.contents.getValue("deal1"))
        assertEquals(1, env.api.creationCalls)
    }

    @Test fun `missing authentication preserves pending and reports reconnect`() = runTest(dispatcher) {
        preparePending()
        val before = env.disk.text
        env.token = null
        val vm = model()
        vm.create(env.source)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertTrue((vm.uiState.value as OutputUiState.Error).message.contains("Connect Spotify"))
        env.token = "restored"
        env.api.appendFailure = null
        vm.create(env.source)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is OutputUiState.Success)
    }

    @Test fun `unclear creation never guesses or creates again after reconstruction`() = runTest(dispatcher) {
        env.api.loseCreate = true
        val vm = model()
        vm.create(env.source)
        submitName(vm)
        assertTrue((vm.uiState.value as OutputUiState.Error).message.contains("recreation is blocked"))
        val fresh = model(env.restart())
        advanceUntilIdle()
        fresh.create(env.source)
        advanceUntilIdle()
        assertEquals(1, env.api.creationCalls)
        assertEquals("Resume Pending Session", fresh.opportunityState.value.primaryActionText)
        assertTrue(events.any { it.failureCategory == "UNRESOLVED_CREATION" })
    }

    @Test fun `corrupt durable state is surfaced without remote calls or erasure`() = runTest(dispatcher) {
        env.disk.text = "invalid json"
        val vm = model()
        advanceUntilIdle()
        assertFalse(vm.opportunityState.value.canSubmit)
        assertTrue(vm.opportunityState.value.errorMessage!!.contains("read saved"))
        assertEquals("invalid json", env.disk.text)
        assertTrue(env.api.events.isEmpty())
        assertTrue(events.any { it.operationName == "Opportunity restoration" })
    }

    @Test fun `persistence failure blocks further work and asks for restart`() = runTest(dispatcher) {
        env.disk.fail = { true }
        val vm = model()
        vm.create(env.source)
        submitName(vm)
        assertEquals(0, env.api.creationCalls)
        assertTrue((vm.uiState.value as OutputUiState.Error).message.contains("reopen"))
        assertFalse(vm.opportunityState.value.canSubmit)
        assertNotNull(vm.opportunityState.value.errorMessage)
    }

    @Test fun `source selection projects each durable rotation independently`() = runTest(dispatcher) {
        env.generate()
        val vm = model()
        vm.selectOpportunitySource("other")
        advanceUntilIdle()
        assertNull(vm.opportunityState.value.savedSource)
        vm.selectOpportunitySource("source")
        advanceUntilIdle()
        assertEquals(5, vm.opportunityState.value.savedSource!!.active!!.undealt.size)
        assertEquals(1, vm.opportunityState.value.savedSource!!.active!!.completedDealCount)
    }

    @Test fun `normal reconstruction restores unfinished active rotation and disabled mode preserves pending`() = runTest(dispatcher) {
        env.generate()
        val fresh = model(env.restart())
        advanceUntilIdle()
        assertEquals(1, fresh.opportunityState.value.savedSource!!.active!!.completedDealCount)
        assertEquals(5, fresh.opportunityState.value.savedSource!!.active!!.undealt.size)
        preparePending()
        val pendingModel = model(env.restart())
        val before = env.disk.text
        pendingModel.setOpportunityEnabled(false)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        pendingModel.setOpportunityEnabled(true)
        advanceUntilIdle()
        assertEquals("Resume Pending Session", pendingModel.opportunityState.value.primaryActionText)
    }

    @Test fun `cancel naming after pending abandonment confirmation leaves original transaction intact`() = runTest(dispatcher) {
        preparePending()
        val before = env.disk.text
        val vm = model()
        vm.startNewRotation(env.source)
        runCurrent()
        vm.confirmNewRotation(vm.confirmationRequest.value!!.requestId)
        runCurrent()
        vm.cancelName(vm.nameRequest.value!!.requestId)
        advanceUntilIdle()
        assertEquals(before, env.disk.text)
        assertEquals(1, env.api.creationCalls)
        assertEquals("Resume Pending Session", vm.opportunityState.value.primaryActionText)
    }

    @Test fun `artist setting changes new delivered order without changing membership or depletion`() = runTest(dispatcher) {
        val other = OpportunityTestEnvironment(dispatcher)
        val plain = model()
        val separated = model(other)
        separated.setArtistSeparation(true)
        plain.create(env.source)
        submitName(plain)
        separated.create(other.source)
        submitName(separated)
        val original = env.api.contents.getValue("deal1")
        val ordered = other.api.contents.getValue("deal1")
        assertEquals(original.toSet(), ordered.toSet())
        assertNotEquals(original, ordered)
        assertEquals(env.store.load().sources.getValue("source").active!!.undealt.map { it.uri },
            other.store.load().sources.getValue("source").active!!.undealt.map { it.uri })
    }

    @Test fun `cancellation keeps submission gate until non cancellable remote call has finished`() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        env.api.beforeMutation = { if (it.startsWith("append")) withContext(NonCancellable) { hold.await() } }
        val vm = model()
        vm.create(env.source)
        runCurrent()
        vm.confirmName(vm.nameRequest.value!!.requestId, "Session")
        runCurrent()
        vm.clearForSessionInvalidation()
        runCurrent()
        assertTrue(vm.isRunning.value)
        vm.create(env.source)
        vm.startNewRotation(env.source)
        assertEquals(1, env.api.creationCalls)
        hold.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.isRunning.value)
        assertEquals(1, env.api.creationCalls)
        assertEquals(OutputUiState.Idle, vm.uiState.value)
    }
}
