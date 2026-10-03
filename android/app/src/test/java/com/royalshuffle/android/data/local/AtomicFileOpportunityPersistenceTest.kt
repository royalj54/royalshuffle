package com.royalshuffle.android.data.local

import com.royalshuffle.android.opportunity.*
import com.royalshuffle.android.output.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileOpportunityPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    // Exercise the persistence adapter on real files; Android AtomicFile itself is unavailable in local JVM tests.
    private class AtomicDocument(private val file: File) : OpportunityAtomicDocument {
        private val temporary = File(file.path + ".new")
        var refusePromotion = false
        var failWrites = false
        var rollbacks = 0
        override fun openRead() = file.inputStream()
        override fun startWrite(): FileOutputStream {
            file.parentFile!!.mkdirs()
            if (failWrites) return object : FileOutputStream(temporary) {
                override fun write(bytes: ByteArray) { throw IOException("write failed") }
            }
            return FileOutputStream(temporary)
        }
        override fun finishWrite(stream: FileOutputStream) {
            stream.close()
            if (!refusePromotion) Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        override fun failWrite(stream: FileOutputStream) { rollbacks++; stream.close(); temporary.delete() }
    }

    @Test fun `real file store reloads exact state and pending payload`() {
        val file = File(folder.root, "private/opportunity/state.json")
        val persistence = AtomicFileOpportunityPersistence(file, AtomicDocument(file))
        assertNull(persistence.read())
        val planner = OpportunityPlanner(OutputPlanner(OccurrenceShuffler { it }))
        val candidate = planner.candidate(listOf(OutputPlaylistItem("spotify:track:a", durationMs = 1)))
        val store = OpportunityStore(persistence)
        store.beginRotation("source", candidate)
        val p = store.stage(planner.prepare("source", candidate), "Deal")
        val loaded = OpportunityStore(AtomicFileOpportunityPersistence(file, AtomicDocument(file))).load()
        assertEquals(p.uris, loaded.sources.getValue("source").pending!!.uris)
        assertEquals(candidate.undealt, loaded.sources.getValue("source").active!!.undealt)
    }

    @Test fun `failed write rolls back and preserves previous bytes`() {
        val file = File(folder.root, "state.json")
        val atomic = AtomicDocument(file)
        val persistence = AtomicFileOpportunityPersistence(file, atomic)
        persistence.replace("previous")
        atomic.failWrites = true
        assertThrows(IOException::class.java) { persistence.replace("next") }
        assertEquals("previous", persistence.read())
        assertEquals(1, atomic.rollbacks)
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun `silently failed promotion is rejected without rolling back a finished file`() {
        val file = File(folder.root, "state.json")
        val atomic = AtomicDocument(file)
        val persistence = AtomicFileOpportunityPersistence(file, atomic)
        persistence.replace("previous")
        atomic.refusePromotion = true
        assertThrows(IllegalStateException::class.java) { persistence.replace("next") }
        assertEquals("previous", persistence.read())
        assertEquals(0, atomic.rollbacks)
    }

    @Test fun `unreadable existing document is an error rather than missing state`() {
        val file = File(folder.root, "state.json")
        file.mkdir()
        assertThrows(IOException::class.java) { AtomicFileOpportunityPersistence(file, AtomicDocument(file)).read() }
    }
}
