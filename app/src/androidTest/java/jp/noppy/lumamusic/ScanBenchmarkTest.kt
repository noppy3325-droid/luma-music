package jp.noppy.lumamusic

import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import jp.noppy.lumamusic.data.MusicFolder
import jp.noppy.lumamusic.data.LibraryDatabase
import jp.noppy.lumamusic.data.LibraryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanBenchmarkTest {
    @Test fun discoveryPrecedesMetadataAndUnchangedScanDoesNotOpenFiles() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val provider=Uri.parse("content://jp.noppy.lumamusic.test.bench")
        fun configure(count:Int=32, deny:Boolean=false, sizeExtra:Long=0, sidecar:Boolean=false) { ctx.contentResolver.call(provider,"configure",null,Bundle().apply { putInt("count",count); putLong("delay",40); putLong("sizeExtra",sizeExtra); putBoolean("deny",deny);putBoolean("sidecar",sidecar) }) }
        fun reset() { ctx.contentResolver.call(provider,"reset",null,null) }
        val db=Room.inMemoryDatabaseBuilder(ctx,LibraryDatabase::class.java).build()
        val library=LibraryRepository(ctx,db)
        val folder=MusicFolder(DocumentsContract.buildTreeDocumentUri(provider.authority!!,"root").toString(),"Pipeline fixture")
        try {
            configure(); reset(); library.dao.folder(folder)
            val job=launch { library.rescan() }
            val early=withTimeout(15000) { library.tracks.first { rows -> rows.size == 32 && rows.any { it.metadataState == "PENDING" } } }
            assertEquals(32,early.size)
            assertTrue("Discovery must be visible while the scanner is running",library.scan.value.running)
            job.join()
            assertEquals("COMPLETE",library.scan.value.status)
            assertTrue(library.dao.tracksInFolder(folder.uri).all { it.metadataState=="READY" && it.detailState=="PENDING" && it.artwork==null })
            val sample=library.dao.tracksInFolder(folder.uri).first()
            assertEquals("READY",library.ensureDetails(sample.uri)!!.detailState)
            assertEquals(44100,library.dao.track(sample.uri)!!.sampleRate)
            reset(); library.ensureDetails(sample.uri)
            assertEquals("Stage C must reuse its persisted cache",0,ctx.contentResolver.call(provider,"counts",null,null)!!.getInt("opens"))
            library.saveLyrics(sample.uri,"Manual lyrics")
            assertEquals("Manual lyrics",library.lyrics(sample))
            reset(); library.rescan()
            val counts=ctx.contentResolver.call(provider,"counts",null,null)!!
            assertEquals("One projected query for the directory; no per-file queries",1,counts.getInt("queries"))
            assertEquals("Unchanged files must never be opened",0,counts.getInt("opens"))
            assertEquals(32,library.scan.value.metrics.cached)
            configure(sidecar=true)
            val sidecar=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder.uri),"sidecar").toString()
            library.dao.upsertTracks(listOf(jp.noppy.lumamusic.data.Track(sidecar,"._backup")))
            library.dao.refs(listOf(jp.noppy.lumamusic.data.TrackFolder(sidecar,folder.uri)))
            library.rescan()
            assertEquals("AppleDouble sidecars are metadata, including legacy rows",32,library.dao.tracksInFolder(folder.uri).size)
            configure(sizeExtra=1); reset(); library.rescan()
            assertEquals("A size change must invalidate the cache even if mtime is unchanged",32,library.scan.value.metrics.parsed)
            assertTrue(ctx.contentResolver.call(provider,"counts",null,null)!!.getInt("opens")>0)
            val cancelled=launch { library.rescan(force=true) }
            withTimeout(15000) { library.tracks.first { rows -> rows.any { it.metadataState=="PENDING" } } }
            library.cancelScan(); cancelled.join()
            assertEquals("CANCELLED",library.scan.value.status)
            assertEquals("Cancellation must retain discovered references",32,library.dao.tracksInFolder(folder.uri).size)
            configure(); library.rescan()
            configure(deny=true); library.rescan()
            assertEquals("PARTIAL_FAILURE",library.scan.value.status)
            assertEquals("Permission loss must retain references",32,library.dao.tracksInFolder(folder.uri).size)
            configure(count=31); library.rescan()
            assertEquals("Only explicitly confirmed deletions remove references",31,library.dao.tracksInFolder(folder.uri).size)
        } finally { configure(count=200); db.close() }
    }
    @Test fun synthetic200ColdAndUnchangedScans() = runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val db=androidx.room.Room.inMemoryDatabaseBuilder(ctx,jp.noppy.lumamusic.data.LibraryDatabase::class.java).build()
        val library=jp.noppy.lumamusic.data.LibraryRepository(ctx,db)
        val uri=DocumentsContract.buildTreeDocumentUri("jp.noppy.lumamusic.test.bench","root")
        val folder=MusicFolder(uri.toString(),"Benchmark synthetic 200 WAV")
        library.dao.folder(folder)
        repeat(2) { run ->
            ctx.contentResolver.call(Uri.parse("content://jp.noppy.lumamusic.test.bench"),"reset",null,null)
            val start=SystemClock.elapsedRealtime()
            val first=async { library.tracks.first { it.any { t -> t.uri.startsWith(uri.toString()) } }; SystemClock.elapsedRealtime()-start }
            library.rescan()
            val elapsed=SystemClock.elapsedRealtime()-start
            val rows=library.dao.tracksInFolder(uri.toString())
            if (rows.size != 200) { first.cancel(); fail("Expected 200 synthetic rows; actual=${rows.size}; scan=${library.scan.value}") }
            val initial=withTimeout(60000) { first.await() }
            val counts=ctx.contentResolver.call(Uri.parse("content://jp.noppy.lumamusic.test.bench"),"counts",null,null)!!
            val record="run=$run count=${rows.size} firstMs=$initial totalMs=$elapsed queries=${counts.getInt("queries")} opens=${counts.getInt("opens")}"
            android.util.Log.i("LumaScanBenchmark",record)
            assertEquals(200, rows.size)
        }
        library.dao.clearRefs(folder.uri); library.dao.deleteFolder(folder.uri); db.close()
    }
}
