package jp.noppy.lumamusic.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String = "不明なアーティスト",
    val album: String = "不明なアルバム",
    val albumArtist: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val duration: Long = 0,
    val mime: String = "",
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val artwork: String? = null,
    val externalLyrics: String? = null,
    val embeddedLyrics: String? = null,
    val modified: Long = 0,
    val added: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val size: Long = 0,
    @ColumnInfo(defaultValue = "'READY'") val metadataState: String = "PENDING",
    @ColumnInfo(defaultValue = "'PENDING'") val detailState: String = "PENDING",
    val artworkThumbnail: String? = null,
)

@Entity(tableName = "audio_analysis")
data class AudioAnalysisCache(@PrimaryKey val uri: String, val identity: String, val version: Int = 1, val estimatedLufs: Double?, val peak: Double, val toneGains: String, val frames: Long, val sampleRate: Int, val complete: Boolean = false, val error: String? = null)
@Entity(tableName = "dsp_presets")
data class DspPreset(@PrimaryKey val name: String, val bands: String, val preamp: Float)

@Entity(tableName = "folders")
data class MusicFolder(@PrimaryKey val uri: String, val name: String, val error: String? = null)
@Entity(tableName = "track_folders", primaryKeys = ["trackUri", "folderUri"], indices = [Index("folderUri")])
data class TrackFolder(val trackUri: String, val folderUri: String)
@Entity(tableName = "user_tracks")
data class UserTrack(@PrimaryKey val uri: String, val favorite: Boolean = false, val lyrics: String? = null)
@Entity(tableName = "playlists")
data class MusicPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val cover: String? = null)
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "entryId"], indices = [Index("playlistId")])
data class PlaylistEntry(val playlistId: Long, val entryId: String, val trackUri: String, val position: Int)

@Dao
interface LibraryDao {
    @Query("SELECT DISTINCT t.* FROM tracks t INNER JOIN track_folders f ON t.uri=f.trackUri ORDER BY t.added DESC")
    fun tracks(): Flow<List<Track>>
    @Query("SELECT * FROM folders") fun folders(): Flow<List<MusicFolder>>
    @Query("SELECT * FROM folders") suspend fun folderList(): List<MusicFolder>
    @Query("SELECT * FROM tracks WHERE uri=:uri") suspend fun track(uri: String): Track?
    @Query("SELECT * FROM tracks") suspend fun trackList(): List<Track>
    @Query("SELECT * FROM track_folders WHERE folderUri=:uri") suspend fun refsForFolder(uri: String): List<TrackFolder>
    @Query("DELETE FROM track_folders WHERE folderUri=:folder AND trackUri=:track") suspend fun removeRef(folder: String, track: String)
    @Query("UPDATE tracks SET externalLyrics=:lyrics WHERE uri=:uri") suspend fun externalLyrics(uri: String, lyrics: String?)
    @Query("UPDATE tracks SET artwork=:art, artworkThumbnail=:thumbnail, embeddedLyrics=:lyrics, bitDepth=COALESCE(:bits, bitDepth), sampleRate=COALESCE(:rate, sampleRate), channels=COALESCE(:channels, channels), mime=COALESCE(:mime, mime), detailState=:state WHERE uri=:uri AND modified=:modified AND size=:size")
    suspend fun details(uri: String, modified: Long, size: Long, art: String?, thumbnail: String?, lyrics: String?, bits: Int?, rate: Int?, channels: Int?, mime: String?, state: String)
    @Query("SELECT * FROM audio_analysis WHERE uri=:uri") suspend fun analysis(uri: String): AudioAnalysisCache?
    @Upsert suspend fun saveAnalysis(value: AudioAnalysisCache)
    @Query("DELETE FROM audio_analysis WHERE uri IN (SELECT trackUri FROM track_folders WHERE folderUri=:folderUri)") suspend fun deleteAnalysisForFolder(folderUri: String)
    @Query("SELECT * FROM dsp_presets ORDER BY name") fun presets(): Flow<List<DspPreset>>
    @Upsert suspend fun upsertPreset(value: DspPreset)
    @Query("DELETE FROM dsp_presets WHERE name=:name") suspend fun deletePreset(name: String)
    @Query("SELECT DISTINCT t.* FROM tracks t INNER JOIN track_folders f ON t.uri=f.trackUri WHERE f.folderUri=:folder")
    suspend fun tracksInFolder(folder: String): List<Track>
    @Query("SELECT * FROM user_tracks") fun userTracks(): Flow<List<UserTrack>>
    @Query("SELECT * FROM user_tracks WHERE uri=:uri") suspend fun userTrack(uri: String): UserTrack?
    @Query("SELECT * FROM playlists ORDER BY name") fun playlists(): Flow<List<MusicPlaylist>>
    @Query("SELECT * FROM playlist_entries ORDER BY position") fun entries(): Flow<List<PlaylistEntry>>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlistId=:id") suspend fun nextPosition(id: Long): Int
    @Upsert suspend fun upsertTracks(tracks: List<Track>)
    @Upsert suspend fun folder(folder: MusicFolder)
    @Upsert suspend fun userTrack(track: UserTrack)
    @Upsert suspend fun playlist(playlist: MusicPlaylist): Long
    @Upsert suspend fun entries(entries: List<PlaylistEntry>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun refs(refs: List<TrackFolder>)
    @Query("DELETE FROM track_folders WHERE folderUri=:uri") suspend fun clearRefs(uri: String)
    @Query("DELETE FROM folders WHERE uri=:uri") suspend fun deleteFolder(uri: String)
    @Query("DELETE FROM playlist_entries WHERE playlistId=:id") suspend fun clearPlaylist(id: Long)
    @Query("DELETE FROM playlists WHERE id=:id") suspend fun deletePlaylist(id: Long)
}

@Database(entities = [Track::class, MusicFolder::class, TrackFolder::class, UserTrack::class, MusicPlaylist::class, PlaylistEntry::class, AudioAnalysisCache::class, DspPreset::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN size INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN metadataState TEXT NOT NULL DEFAULT 'READY'")
        db.execSQL("ALTER TABLE tracks ADD COLUMN detailState TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("ALTER TABLE tracks ADD COLUMN artworkThumbnail TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS audio_analysis (uri TEXT NOT NULL, identity TEXT NOT NULL, version INTEGER NOT NULL, estimatedLufs REAL, peak REAL NOT NULL, toneGains TEXT NOT NULL, frames INTEGER NOT NULL, sampleRate INTEGER NOT NULL, complete INTEGER NOT NULL, error TEXT, PRIMARY KEY(uri))")
        db.execSQL("CREATE TABLE IF NOT EXISTS dsp_presets (name TEXT NOT NULL, bands TEXT NOT NULL, preamp REAL NOT NULL, PRIMARY KEY(name))")
    }
}
