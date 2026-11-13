package jp.noppy.lumamusic.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.SessionCommand

internal const val QUEUE_ITEM_URI = "uri"
internal const val QUEUE_PLAY_NEXT = "playNext"
internal val addQueueItemCommand by lazy {
    SessionCommand("jp.noppy.lumamusic.QUEUE_ADD", Bundle.EMPTY)
}

internal fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

internal fun Timeline.shuffledOrder(): List<Int> = collectOrder(
    getFirstWindowIndex(true), Player.REPEAT_MODE_OFF, shuffle = true,
)

/** The remaining items for explicit navigation. Repeat One loops only at automatic completion. */
internal fun Timeline.upcomingOrder(current: Int, repeat: Int, shuffle: Boolean): List<Int> {
    if (current !in 0 until windowCount) return emptyList()
    val navigationRepeat = if (repeat == Player.REPEAT_MODE_ALL) repeat else Player.REPEAT_MODE_OFF
    return collectOrder(getNextWindowIndex(current, navigationRepeat, shuffle), navigationRepeat, shuffle, current)
}

private fun Timeline.collectOrder(first: Int, repeat: Int, shuffle: Boolean, stopAt: Int = C.INDEX_UNSET): List<Int> = buildList {
    var index = first
    while (index in 0 until windowCount && index != stopAt && size < windowCount) {
        add(index)
        index = getNextWindowIndex(index, repeat, shuffle)
    }
}

/** Remap original timeline indices after adding one item, then place it in the actual shuffle order. */
internal fun List<Int>.afterQueueInsertion(insertionIndex: Int, after: Int?): IntArray {
    val result = IntArray(size + 1)
    var write = 0
    for (index in this) {
        result[write++] = if (index >= insertionIndex) index + 1 else index
        if (index == after) result[write++] = insertionIndex
    }
    if (after == null) result[write] = insertionIndex
    return result
}

internal fun List<Int>.isWindowPermutation(count: Int): Boolean {
    if (size != count) return false
    val seen = BooleanArray(count)
    for (index in this) {
        if (index !in seen.indices || seen[index]) return false
        seen[index] = true
    }
    return true
}
