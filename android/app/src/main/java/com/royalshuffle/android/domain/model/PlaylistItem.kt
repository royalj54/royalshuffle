package com.royalshuffle.android.domain.model

data class PlaylistItem(
    val uri: String,
    val name: String,
    val artists: List<String> = emptyList(),
)
