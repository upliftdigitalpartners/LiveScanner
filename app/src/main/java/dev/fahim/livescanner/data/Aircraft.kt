package dev.fahim.livescanner.data

/** A single aircraft contact from an ADS-B feed (adsb.lol). */
data class Aircraft(
    val hex: String,
    val callsign: String?,
    val registration: String?,
    val type: String?,
    val lat: Double,
    val lon: Double,
    val altitudeFt: Int?,        // null when on the ground or unknown
    val onGround: Boolean,
    val groundSpeedKt: Double,
    val trackDeg: Double,        // 0 = north, 90 = east
    val squawk: String?,
    val emergency: Boolean,
    val verticalRateFpm: Int?,   // climb (+) / descent (-) rate, feet per minute
    val category: String?,       // ADS-B emitter category (A1 light … A5 heavy, A7 rotorcraft)
    val seenNanos: Long,         // System.nanoTime() at fetch, used for dead-reckoning
)
