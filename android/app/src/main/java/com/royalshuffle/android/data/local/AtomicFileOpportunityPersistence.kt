package com.royalshuffle.android.data.local

import android.content.Context
import android.util.AtomicFile
import com.royalshuffle.android.opportunity.OpportunityPersistence
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal interface OpportunityAtomicDocument {
    fun openRead(): InputStream
    fun startWrite(): FileOutputStream
    fun finishWrite(stream: FileOutputStream)
    fun failWrite(stream: FileOutputStream)
}

private class AndroidAtomicDocument(file: File) : OpportunityAtomicDocument {
    private val atomic = AtomicFile(file)
    override fun openRead() = atomic.openRead()
    override fun startWrite() = atomic.startWrite()
    override fun finishWrite(stream: FileOutputStream) = atomic.finishWrite(stream)
    override fun failWrite(stream: FileOutputStream) = atomic.failWrite(stream)
}

/** Application-private journal. Construct/use the store off the main thread. */
class AtomicFileOpportunityPersistence internal constructor(
    private val file: File,
    private val atomic: OpportunityAtomicDocument,
) : OpportunityPersistence {
    private constructor(file: File) : this(file, AndroidAtomicDocument(file))
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "opportunity/state.json"))
    override val coordinationKey: String = file.canonicalPath

    override fun read(): String? {
        // AtomicFile restores a backup on openRead. Do not mistake an unreadable file for absence.
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun replace(document: String) {
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(document.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            atomic.finishWrite(stream)
            stream = null // finishWrite closed/promoted it; never roll back a promoted document.
            // Android finishWrite logs some rename failures rather than throwing.
            check(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } == document) {
                "Atomic Opportunity replacement was not confirmed."
            }
        } catch (error: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
    }
}
