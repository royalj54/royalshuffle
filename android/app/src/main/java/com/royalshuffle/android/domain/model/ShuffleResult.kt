package com.royalshuffle.android.domain.model

data class ShuffleResult(
    val playlistId: String,
    val playlistName: String,
    val itemCount: Int,
    val action: Action,
) {
    enum class Action {
        CREATED,
        UPDATED,
    }
}
