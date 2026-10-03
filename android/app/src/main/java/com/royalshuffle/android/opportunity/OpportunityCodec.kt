package com.royalshuffle.android.opportunity

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

internal object OpportunityCodec {
    fun validate(document: OpportunityDocument) {
        val pendingIds = mutableSetOf<String>()
        val outputs = mutableSetOf<String>()
        document.sources.forEach { (sourceId, source) ->
            checkState(validId(sourceId) && (source.active != null || source.pending != null), "Invalid source state.")
            source.pending?.let { p ->
                checkState(validUuid(p.operationId) && pendingIds.add(p.operationId), "Invalid/duplicate operation ID.")
                checkState(p.expectedRotationId == source.active?.rotationId && p.expectedRevision == (source.active?.revision ?: 0) &&
                    p.expectedCompletedDealCount == (source.active?.completedDealCount ?: 0) &&
                    p.expectedSnapshot == source.active?.let(::fingerprint), "Pending active state mismatch.")
                val selected = if (p.action == OpportunityAction.NEW_ROTATION) {
                    checkState(p.candidate != null && p.candidate.completedDealCount == 0 &&
                        p.candidate.rotationId != source.active?.rotationId, "Invalid candidate rotation.")
                    p.candidate!!
                } else {
                    checkState(p.candidate == null && source.active != null, "Next session requires active rotation.")
                    source.active!!
                }
                checkState(p.uris.isNotEmpty() && p.uris.toSet().size == p.uris.size &&
                    p.uris.all { uri -> selected.undealt.any { it.uri == uri } }, "Invalid pending membership.")
                checkState(p.creationName.isNotBlank() && p.creationName == p.creationName.trim(), "Invalid creation name.")
                p.outputId?.let { checkState(validId(it) && it !in document.sources && outputs.add(it), "Conflicting output identity.") }
                checkState((p.phase in setOf(OpportunityPhase.STAGED, OpportunityPhase.CREATING)) == (p.outputId == null), "Invalid output phase.")
                p.attempt?.let { checkState(it.outputId == p.outputId && it.uris == p.uris &&
                    p.phase in setOf(OpportunityPhase.POPULATING, OpportunityPhase.DELIVERED), "Attempt identity/payload mismatch.") }
                checkState(p.phase != OpportunityPhase.POPULATING || p.attempt != null, "Missing delivery attempt.")
                checkState(!p.exactContentsConfirmed || p.phase == OpportunityPhase.DELIVERED, "Invalid exact-content evidence.")
                checkState(p.phase != OpportunityPhase.DELIVERED || p.exactContentsConfirmed || p.attempt?.complete == true,
                    "Delivered state lacks evidence.")
            }
        }
    }

    fun encode(document: OpportunityDocument): String {
        validate(document)
        return JSONObject().put("version", OpportunityDocument.VERSION).put("sources", JSONObject().apply {
            document.sources.forEach { (id, source) -> put(id, JSONObject()
                .put("active", source.active?.let(::rotationJson) ?: JSONObject.NULL)
                .put("pending", source.pending?.let(::pendingJson) ?: JSONObject.NULL)) }
        }).toString()
    }

    fun decode(text: String): OpportunityDocument {
        // Parse objects ourselves: reject duplicate keys on Android as well as desktop org.json.
        val tokens = JSONTokener(text)
        val root = parse(tokens) as? JSONObject ?: throw OpportunityStateException("Invalid root.")
        checkState(tokens.nextClean() == '\u0000', "Trailing state data.")
        root.fields("version", "sources")
        checkState(root.integer("version") == OpportunityDocument.VERSION, "Unsupported Opportunity version.")
        val sources = root.getJSONObject("sources")
        val result = linkedMapOf<String, OpportunitySource>()
        sources.keys().forEach { id ->
            val row = sources.getJSONObject(id).fields("active", "pending")
            result[id] = OpportunitySource(if (row.isNull("active")) null else rotation(row.getJSONObject("active")),
                if (row.isNull("pending")) null else pending(row.getJSONObject("pending")))
        }
        return OpportunityDocument(result).also(::validate)
    }

    private fun parse(tokens: JSONTokener): Any {
        return when (tokens.nextClean()) {
            '{' -> JSONObject().apply {
                if (tokens.nextClean() != '}') {
                    tokens.back()
                    while (true) {
                        checkState(tokens.nextClean() == '"', "Object key must be quoted.")
                        val key = tokens.nextString('"')
                        checkState(!has(key) && tokens.nextClean() == ':', "Duplicate key or invalid object.")
                        put(key, parse(tokens))
                        val next = tokens.nextClean()
                        if (next == '}') break
                        checkState(next == ',', "Invalid object separator.")
                    }
                }
            }
            '[' -> JSONArray().apply {
                if (tokens.nextClean() != ']') {
                    tokens.back()
                    while (true) {
                        put(parse(tokens))
                        val next = tokens.nextClean()
                        if (next == ']') break
                        checkState(next == ',', "Invalid array separator.")
                    }
                }
            }
            '"' -> tokens.nextString('"')
            else -> {
                tokens.back()
                val token = tokens.nextTo(",]} \t\r\n")
                when (token) {
                    "null" -> JSONObject.NULL
                    "true" -> true
                    "false" -> false
                    else -> {
                        checkState(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")), "Invalid JSON value.")
                        if (token.contains('.') || token.contains('e', ignoreCase = true)) token.toDouble()
                        else token.toLongOrNull() ?: throw OpportunityStateException("State integer overflow.")
                    }
                }
            }
        }
    }

    private fun rotationJson(r: OpportunityRotation) = JSONObject().put("rotation_id", r.rotationId)
        .put("original_count", r.originalUniqueCount).put("completed_count", r.completedDealCount)
        .put("undealt", JSONArray().apply { r.undealt.forEach { put(JSONObject().put("uri", it.uri)
            .put("duration_ms", it.durationMs).put("artist_id", it.primaryArtistId ?: JSONObject.NULL)) } })
    // Explicit ordered encoding makes stale-state identity independent of JSONObject key iteration order.
    fun fingerprint(r: OpportunityRotation): String {
        val canonical = JSONArray().put(r.rotationId).put(r.originalUniqueCount).put(r.completedDealCount)
            .put(JSONArray().apply { r.undealt.forEach { put(JSONArray().put(it.uri).put(it.durationMs)
                .put(it.primaryArtistId ?: JSONObject.NULL)) } }).toString()
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun rotation(j: JSONObject): OpportunityRotation {
        j.fields("rotation_id", "original_count", "completed_count", "undealt")
        val items = j.getJSONArray("undealt")
        return OpportunityRotation(j.string("rotation_id"), j.integer("original_count"), j.integer("completed_count"),
            (0 until items.length()).map { index ->
                val item = items.getJSONObject(index).fields("uri", "duration_ms", "artist_id")
                OpportunityTrack(item.string("uri"), item.long("duration_ms"), item.nullableString("artist_id"))
            })
    }
    private fun pendingJson(p: OpportunityPending) = JSONObject().put("operation_id", p.operationId).put("action", p.action.name)
        .put("expected_rotation_id", p.expectedRotationId ?: JSONObject.NULL).put("expected_revision", p.expectedRevision)
        .put("expected_completed_count", p.expectedCompletedDealCount).put("uris", JSONArray(p.uris))
        .put("expected_snapshot", p.expectedSnapshot ?: JSONObject.NULL)
        .put("candidate", p.candidate?.let(::rotationJson) ?: JSONObject.NULL).put("creation_name", p.creationName)
        .put("phase", p.phase.name).put("output_id", p.outputId ?: JSONObject.NULL)
        .put("exact_contents_confirmed", p.exactContentsConfirmed).put("attempt", p.attempt?.let { a ->
            JSONObject().put("attempt_id", a.attemptId).put("output_id", a.outputId).put("uris", JSONArray(a.uris))
                .put("receipts", JSONArray().apply { a.receipts.forEach { r -> put(JSONObject().put("index", r.index)
                    .put("method", r.method.name).put("start", r.start).put("end", r.end).put("status", r.status).put("snapshot_id", r.snapshotId)) } })
        } ?: JSONObject.NULL)
    private fun pending(j: JSONObject): OpportunityPending {
        j.fields("operation_id", "action", "expected_rotation_id", "expected_revision", "expected_completed_count", "uris",
            "expected_snapshot", "candidate", "creation_name", "phase", "output_id", "exact_contents_confirmed", "attempt")
        val attempt = if (j.isNull("attempt")) null else j.getJSONObject("attempt").let { a ->
            a.fields("attempt_id", "output_id", "uris", "receipts")
            val rows = a.getJSONArray("receipts")
            OpportunityAttempt(a.string("attempt_id"), a.string("output_id"), a.strings("uris"),
                (0 until rows.length()).map { index ->
                    val r = rows.getJSONObject(index).fields("index", "method", "start", "end", "status", "snapshot_id")
                    OpportunityReceipt(r.integer("index"), OpportunityWriteMethod.valueOf(r.string("method")),
                        r.integer("start"), r.integer("end"), r.integer("status"), r.string("snapshot_id"))
                })
        }
        return OpportunityPending(j.string("operation_id"), OpportunityAction.valueOf(j.string("action")),
            j.nullableString("expected_rotation_id"), j.integer("expected_revision"), j.integer("expected_completed_count"),
            j.nullableString("expected_snapshot"),
            j.strings("uris"), if (j.isNull("candidate")) null else rotation(j.getJSONObject("candidate")),
            j.string("creation_name"), OpportunityPhase.valueOf(j.string("phase")), j.nullableString("output_id"), attempt,
            j.get("exact_contents_confirmed") as Boolean)
    }
    private fun JSONObject.fields(vararg names: String): JSONObject {
        checkState(keys().asSequence().toSet() == names.toSet(), "Unexpected or missing state fields.")
        return this
    }
    private fun JSONObject.string(key: String) = get(key) as String
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else string(key)
    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        checkState(value is Int || value is Long, "Expected integer state value.")
        return (value as Number).toLong()
    }
    private fun JSONObject.integer(key: String): Int {
        val value = long(key)
        checkState(value in Int.MIN_VALUE..Int.MAX_VALUE, "State integer overflow.")
        return value.toInt()
    }
    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { rows ->
        (0 until rows.length()).map { rows.get(it) as String }
    }
}
