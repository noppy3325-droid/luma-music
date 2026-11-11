package jp.noppy.lumamusic.ui

import jp.noppy.lumamusic.core.albumKey
import jp.noppy.lumamusic.core.normalizedSearch
import jp.noppy.lumamusic.data.MusicPlaylist
import jp.noppy.lumamusic.data.Track

internal data class SearchAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val track: Track,
)

internal data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<SearchAlbum> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
) {
    val isEmpty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

internal class SearchIndex(tracks: List<Track>, playlists: List<MusicPlaylist>) {
    private val songs = tracks.map { track ->
        track to normalizedSearch(listOf(track.title, track.artist, track.album, track.albumArtist, track.genre).joinToString(" "))
    }
    private val albums = tracks.distinctBy { albumKey(it.album, it.albumArtist, it.artist) }.map { track ->
        SearchAlbum(
            albumKey(track.album, track.albumArtist, track.artist),
            track.album,
            track.albumArtist.ifBlank { track.artist },
            track,
        ) to normalizedSearch(track.album)
    }
    private val artists = tracks.map(Track::artist).distinct().map { it to normalizedSearch(it) }
    private val lists = playlists.map { it to normalizedSearch(it.name) }

    fun search(query: String): SearchResults {
        val terms = normalizedSearch(query).trim().split(Whitespace).filter(String::isNotEmpty)
        if (terms.isEmpty()) return SearchResults()

        fun <T> List<Pair<T, String>>.matching(): List<T> = mapNotNull { (value, searchable) ->
            value.takeIf { terms.all { term -> searchable.contains(term) } }
        }

        return SearchResults(songs.matching(), albums.matching(), artists.matching(), lists.matching())
    }

    companion object {
        private val Whitespace = Regex("\\s+")
    }
}
