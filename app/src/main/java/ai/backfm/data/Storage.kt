package ai.backfm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import ai.backfm.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "records", primaryKeys = ["kind", "id"])
data class Record(@ColumnInfo val kind: String, @ColumnInfo val id: String, @ColumnInfo val json: String)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records") fun observe(): Flow<List<Record>>
    @Query("SELECT * FROM records") suspend fun snapshot(): List<Record>
    @Query("SELECT * FROM records WHERE kind = :kind") suspend fun read(kind: String): List<Record>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)
    @Query("DELETE FROM records WHERE kind = :kind AND id = :id") suspend fun delete(kind: String, id: String)
    @Query("DELETE FROM records WHERE kind IN ('trip','visit','pick')") suspend fun clearTrip()
    @Query("DELETE FROM records") suspend fun clearAll()
}
@Database(entities = [Record::class], version = 1, exportSchema = false)
abstract class BackfmDatabase : RoomDatabase() { abstract fun records(): RecordDao }

/** Explicit version-one codec. Sensitive content never enters logs or shared storage. */
object Codec {
    private fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply { values.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun target(t: DestinationIdentity) = obj("name" to t.name, "address" to t.address, "poiId" to t.poiId, "poiSubId" to t.poiSubId, "poiProvider" to t.poiProvider)
    private fun target(j: JSONObject) = DestinationIdentity(j.getString("name"), j.getString("address"), j.optString("poiId"), if (j.isNull("poiSubId")) null else j.getString("poiSubId"), j.optString("poiProvider"))
    fun trip(t: TripSession) = Record("trip", t.id, obj("id" to t.id, "start" to t.startTime, "end" to t.endTime, "return" to t.returnDestination, "status" to t.status.name, "source" to t.dataSource.name, "consent" to t.consentStatus, "returnTarget" to t.returnTarget?.let(::target)).toString())
    fun trip(r: Record): TripSession = JSONObject(r.json).let { TripSession(it.getString("id"), it.getLong("start"), if (it.isNull("end")) null else it.getLong("end"), it.getString("return"), TripStatus.valueOf(it.getString("status")), DataSource.valueOf(it.getString("source")), it.getBoolean("consent"), it.optJSONObject("returnTarget")?.let(::target)) }
    fun visit(v: VisitCandidate) = Record("visit", v.id, obj("id" to v.id, "trip" to v.tripId, "name" to v.placeName, "time" to v.arrivalTime, "source" to v.source.name, "verification" to v.verification.name, "sequence" to v.sequence, "key" to v.eventKey).toString())
    fun visit(r: Record): VisitCandidate = JSONObject(r.json).let { VisitCandidate(it.getString("id"), it.getString("trip"), it.getString("name"), it.getLong("time"), DataSource.valueOf(it.getString("source")), Verification.valueOf(it.getString("verification")), it.getInt("sequence"), it.getString("key")) }
    fun pick(p: MemoryPick) = Record("pick", p.id, obj("id" to p.id, "trip" to p.tripId, "name" to p.nickname, "visit" to p.visitId, "emotion" to p.emotion, "note" to p.note, "elapsed" to p.elapsedMs).toString())
    fun pick(r: Record): MemoryPick = JSONObject(r.json).let { MemoryPick(it.getString("id"), it.getString("trip"), it.getString("name"), it.getString("visit"), it.getString("emotion"), it.getString("note"), it.getLong("elapsed")) }
    fun episode(e: Episode): Record {
        val lines = JSONArray()
        e.lines.forEach { lines.put(obj("section" to it.section, "text" to it.text, "evidence" to JSONArray(it.evidence.toList()))) }
        return Record("episode", e.id, obj("id" to e.id, "trip" to e.tripId, "title" to e.title, "created" to e.createdAt, "duration" to e.targetSeconds, "lines" to lines, "source" to e.source.name, "saved" to e.saved, "audio" to e.audioPath).toString())
    }
    fun episode(r: Record): Episode = JSONObject(r.json).let { j ->
        val array = j.getJSONArray("lines")
        val lines = (0 until array.length()).map { i -> array.getJSONObject(i).let { l ->
            val refs = l.getJSONArray("evidence")
            ScriptLine(l.getString("section"), l.getString("text"), (0 until refs.length()).map { refs.getString(it) }.toSet())
        } }
        Episode(j.getString("id"), j.getString("trip"), j.getString("title"), j.getLong("created"), j.getInt("duration"), lines, DataSource.valueOf(j.getString("source")), j.getBoolean("saved"), if (j.isNull("audio")) null else j.getString("audio"))
    }
}

private val Context.settingsStore by preferencesDataStore("privacy")
data class PrivacySettings(val consent: Boolean = false, val guardian: Boolean = false, val demo: Boolean = true, val voice: Boolean = true)
class Settings(private val context: Context) {
    private val consent = booleanPreferencesKey("consent")
    private val guardian = booleanPreferencesKey("guardian")
    private val demo = booleanPreferencesKey("demo")
    private val voice = booleanPreferencesKey("voice")
    val flow = context.settingsStore.data.map { PrivacySettings(it[consent] ?: false, it[guardian] ?: false, it[demo] ?: true, it[voice] ?: true) }
    suspend fun consent(value: Boolean, guardianConfirmed: Boolean = false) { context.settingsStore.edit { it[consent] = value; it[guardian] = value && guardianConfirmed } }
    suspend fun demo(value: Boolean) { context.settingsStore.edit { it[demo] = value } }
    suspend fun voice(value: Boolean) { context.settingsStore.edit { it[voice] = value } }
}
