package jp.noppy.lumamusic

import android.app.Application
import androidx.room.Room
import jp.noppy.lumamusic.audio.OutputMonitor
import jp.noppy.lumamusic.data.*

class LumaApp : Application() {
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "luma.db").addMigrations(MIGRATION_1_2).build() }
    val library by lazy { LibraryRepository(this, database) }
    val settings by lazy { SettingsRepository(this) }
    val output by lazy { OutputMonitor(this) }
}
