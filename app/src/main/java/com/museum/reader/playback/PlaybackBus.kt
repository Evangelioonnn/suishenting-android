package com.museum.reader.playback
import com.museum.reader.model.PlaybackSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
/** Observed by UI; published only by the serialized playback controller. */
object PlaybackBus {
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    internal fun publish(value: PlaybackSnapshot) { mutableSnapshot.value = value }
}
