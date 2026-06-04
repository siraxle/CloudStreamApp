package com.example.cloudstreamapp.domain.model

// Band gains are in millibels (100 mb = 1 dB). Range is typically -1500..1500 mb.
enum class EqualizerPreset(val displayName: String, val bands: ShortArray) {
    FLAT("Flat", shortArrayOf(0, 0, 0, 0, 0)),
    BASS_BOOST("Bass", shortArrayOf(900, 600, 0, -300, -300)),
    TREBLE_BOOST("Treble", shortArrayOf(-300, -300, 0, 600, 900)),
    POP("Pop", shortArrayOf(0, 400, 600, 400, 0)),
    ROCK("Rock", shortArrayOf(500, 300, -100, 300, 500)),
    JAZZ("Jazz", shortArrayOf(400, 300, 0, 300, 400)),
    CLASSICAL("Classical", shortArrayOf(500, 300, 0, 400, 600)),
    CUSTOM("Custom", shortArrayOf(0, 0, 0, 0, 0)),
}
