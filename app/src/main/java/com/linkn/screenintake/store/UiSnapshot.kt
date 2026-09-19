package com.linkn.screenintake.store

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Versioned, rebuildable read model. Never synced and never used to perform a write. */
object UiSnapshot {
    private fun file(context: Context, folder: String): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(folder.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.cacheDir, "read-model-$key.json"))
    }
    @Synchronized fun save(context: Context, folder: String) {
        val json = JSONObject().put("version", 1)
            .put("ledger", JSONArray(UiDataCache.ledger.map { JSONArray(listOf(it.date, it.type, it.category, it.amount, it.note, it.index)) }))
            .put("todos", JSONArray(UiDataCache.todos.map { JSONArray(listOf(it.done, it.text, it.domain, it.index, it.dueAt ?: "", it.reminderId ?: "")) }))
            .put("notes", JSONArray(UiDataCache.notes.map { JSONArray(listOf(it.heading, it.content, it.domain, it.index)) }))
            .put("weights", JSONArray(UiDataCache.weights.map { JSONArray(listOf(it.date, it.weightKg, it.note, it.index)) }))
            .put("cards", JSONArray(UiDataCache.cards.map { JSONArray(listOf(it.name, it.type, it.balance, it.index)) }))
            .put("holdings", JSONArray(UiDataCache.holdings.map { JSONArray(listOf(it.market, it.code, it.name, it.shares, it.avgCost, it.index)) }))
            .put("transfers", JSONArray(UiDataCache.transfers.map { JSONArray(listOf(it.date, it.fromCard, it.toCard, it.amount, it.note, it.index)) }))
            .put("meetings", JSONArray(UiDataCache.meetings.map { JSONObject(it.toJson()) }))
            .put("activities", JSONArray(UiDataCache.growthActivities.map { it.toJson() }))
            .put("efforts", JSONArray(UiDataCache.growthEfforts.map { effort -> effort.toJson().put("artifacts", JSONArray(effort.artifacts.map {
                JSONObject().put("name", it.name).put("uri", it.uri).put("mimeType", it.mimeType).put("preview", it.preview)
            })) }))
        val target = file(context, folder)
        val stream = target.startWrite()
        try { stream.write(json.toString().toByteArray()); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }

    @Synchronized fun load(context: Context, folder: String) {
        val generation = DataChangeSignal.currentGeneration()
        runCatching {
            val json = JSONObject(file(context, folder).openRead().bufferedReader().use { it.readText() })
            require(json.getInt("version") == 1)
            fun <T> rows(name: String, decode: (JSONArray) -> T): List<T> {
                val array = json.getJSONArray(name)
                return (0 until array.length()).map { decode(array.getJSONArray(it)) }
            }
            val ledger = rows("ledger") { LedgerRow(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getInt(5)) }
            val todos = rows("todos") { TodoItem(it.getBoolean(0), it.getString(1), it.getString(2), it.getInt(3), it.getString(4).ifBlank { null }, it.getString(5).ifBlank { null }) }
            val notes = rows("notes") { NoteItem(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)) }
            val weights = rows("weights") { WeightRow(it.getString(0), it.getDouble(1), it.getString(2), it.getInt(3)) }
            val cards = rows("cards") { CardAccount(it.getString(0), it.getString(1), it.getDouble(2), it.getInt(3)) }
            val holdings = rows("holdings") { Holding(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getDouble(4), it.getInt(5)) }
            val transfers = rows("transfers") { TransferRow(it.getString(0), it.getString(1), it.getString(2), it.getDouble(3), it.getString(4), it.getInt(5)) }
            fun <T> objects(name: String, decode: (JSONObject) -> T): List<T> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return (0 until array.length()).map { decode(array.getJSONObject(it)) }
            }
            val meetings = objects("meetings") { com.linkn.screenintake.meeting.MeetingRecord.fromJson(it.toString()) }
            val activities = objects("activities", com.linkn.screenintake.growth.GrowthActivity::fromJson)
            val efforts = objects("efforts") { item ->
                val artifacts = item.optJSONArray("artifacts") ?: JSONArray()
                com.linkn.screenintake.growth.GrowthEffort.fromJson(item).copy(artifacts = (0 until artifacts.length()).map { index ->
                    val a = artifacts.getJSONObject(index)
                    com.linkn.screenintake.growth.GrowthArtifact(a.getString("name"), a.getString("uri"), a.getString("mimeType"), a.optString("preview"))
                })
            }
            if (generation == DataChangeSignal.currentGeneration()) {
                UiDataCache.ledger = ledger; UiDataCache.todos = todos; UiDataCache.notes = notes
                UiDataCache.weights = weights; UiDataCache.cards = cards; UiDataCache.holdings = holdings; UiDataCache.transfers = transfers
                UiDataCache.meetings = meetings; UiDataCache.growthActivities = activities; UiDataCache.growthEfforts = efforts
            }
        }
    }
}
