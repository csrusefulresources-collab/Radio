package com.radio.app

enum class Band(val nameRes: Int, val unitRes: Int) {
    FM(R.string.band_fm, R.string.unit_mhz),
    MW(R.string.band_mw, R.string.unit_khz),
    SW(R.string.band_sw, R.string.unit_khz)
}

data class Station(
    val name: String,
    val frequency: String,
    val band: Band,
    val url: String,
    val isUser: Boolean = false
)
