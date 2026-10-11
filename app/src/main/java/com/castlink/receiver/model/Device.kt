package com.castlink.receiver.model

data class Device(
    val name: String,
    val ip: String,
    val signalLevel: Int,
    val tier: Tier,
    val isFavorite: Boolean = false
)

enum class Tier {
    FLAGSHIP,
    MAINSTREAM,
    LEGACY
}
