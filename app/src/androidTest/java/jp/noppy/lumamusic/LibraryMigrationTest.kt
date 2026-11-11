package jp.noppy.lumamusic

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.noppy.lumamusic.data.LibraryDatabase
import jp.noppy.lumamusic.data.MIGRATION_1_2
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Creates the exact exported v1 schema, then lets Room validate the migrated v2 schema. */
@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @Test fun migrationPreservesLibraryAndUserEdits() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema=JSONObject(instrumentation.context.assets.open("library-schema-v1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO tracks(uri,title,artist,album,albumArtist,genre,year,trackNumber,discNumber,duration,mime,modified,added) VALUES('fixture','Saved title','Artist','Album','','','',1,1,1000,'audio/wav',1234,5678)")
            old.execSQL("INSERT INTO folders(uri,name,error) VALUES('folder','Saved folder',NULL)")
            old.execSQL("INSERT INTO track_folders(trackUri,folderUri) VALUES('fixture','folder')")
            old.execSQL("INSERT INTO user_tracks(uri,favorite,lyrics) VALUES('fixture',1,'User edited lyrics')")
            old.execSQL("INSERT INTO playlists(id,name,cover) VALUES(7,'Saved playlist',NULL)")
            old.execSQL("INSERT INTO playlist_entries(playlistId,entryId,trackUri,position) VALUES(7,'entry','fixture',0)")
            old.version=1
        }
        val upgraded=Room.databaseBuilder(context,LibraryDatabase::class.java,name).addMigrations(MIGRATION_1_2).build()
        try {
            upgraded.openHelper.writableDatabase
            val dao=upgraded.library()
            assertEquals("Saved title",dao.track("fixture")!!.title)
            assertEquals("READY",dao.track("fixture")!!.metadataState)
            assertEquals(0L,dao.track("fixture")!!.size)
            assertEquals("PENDING",dao.track("fixture")!!.detailState)
            assertEquals(1,dao.tracksInFolder("folder").size)
            assertTrue(dao.userTrack("fixture")!!.favorite)
            assertEquals("User edited lyrics",dao.userTrack("fixture")!!.lyrics)
            assertEquals("Saved playlist",dao.playlists().first().single().name)
            assertEquals("fixture",dao.entries().first().single().trackUri)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
