package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.data.MusicPlaylist
import jp.noppy.lumamusic.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    @Test fun fullWidthLatinAndKanaMatchRealGroups() {
        val songs = listOf(
            Track("one", "星をつなぐ", "Orangestar", "ナイトフライト"),
            Track("two", "Surges", "Orangestar", "ナイトフライト"),
            Track("three", "光", "別のアーティスト", "ナイトフライト"),
        )
        val playlist = MusicPlaylist(5, "Ｎｉｇｈｔ Ｆｌｉｇｈｔ")
        val index = SearchIndex(songs, listOf(playlist))

        val artist = index.search("ｏｒａｎｇｅＳＴＡＲ")
        assertEquals(listOf("one", "two"), artist.tracks.map { it.uri })
        assertEquals(listOf("Orangestar"), artist.artists)
        assertTrue(artist.albums.isEmpty())

        val album = index.search("ないとふらいと")
        assertEquals(3, album.tracks.size)
        assertEquals(listOf("Orangestar", "別のアーティスト"), album.albums.map { it.artist })
        assertEquals(listOf(playlist), index.search("night flight").playlists)
    }

    @Test fun multiwordSearchAndEmptyQuery() {
        val index = SearchIndex(
            listOf(Track("one", "Surges (feat. IA)", "Orangestar"), Track("two", "Surges", "Other")),
            emptyList(),
        )
        assertEquals(listOf("one"), index.search("orange  ＳＵＲＧＥＳ").tracks.map { it.uri })
        assertTrue(index.search("  ").isEmpty)
        assertTrue(index.search("行方不明").isEmpty)
    }
}
