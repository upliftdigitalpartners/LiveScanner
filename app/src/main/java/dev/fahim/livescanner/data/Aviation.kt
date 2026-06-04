package dev.fahim.livescanner.data

// Plain-language helpers so non-pilots can make sense of the radar.

private val AIRLINES = mapOf(
    "UAL" to "United", "AAL" to "American", "DAL" to "Delta", "SWA" to "Southwest",
    "JBU" to "JetBlue", "NKS" to "Spirit", "FFT" to "Frontier", "ASA" to "Alaska",
    "AAY" to "Allegiant", "HAL" to "Hawaiian", "SCX" to "Sun Country",
    "EDV" to "Endeavor (Delta)", "RPA" to "Republic", "SKW" to "SkyWest",
    "ENY" to "Envoy (American Eagle)", "PDT" to "Piedmont", "GJS" to "GoJet",
    "ASH" to "Mesa", "QXE" to "Horizon", "FDX" to "FedEx", "UPS" to "UPS",
    "ACA" to "Air Canada", "WJA" to "WestJet", "BAW" to "British Airways",
    "DLH" to "Lufthansa", "AFR" to "Air France", "KLM" to "KLM", "UAE" to "Emirates",
    "VIR" to "Virgin Atlantic", "QTR" to "Qatar", "AMX" to "Aeroméxico", "VOI" to "Volaris",
)

private val TYPES = mapOf(
    "B737" to "Boeing 737", "B738" to "Boeing 737-800", "B739" to "Boeing 737-900",
    "B38M" to "Boeing 737 MAX 8", "B39M" to "Boeing 737 MAX 9",
    "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
    "A20N" to "Airbus A320neo", "A21N" to "Airbus A321neo",
    "B752" to "Boeing 757", "B763" to "Boeing 767", "B772" to "Boeing 777",
    "B77W" to "Boeing 777-300ER", "B788" to "Boeing 787-8", "B789" to "Boeing 787-9",
    "A332" to "Airbus A330", "A333" to "Airbus A330-300", "A359" to "Airbus A350",
    "E170" to "Embraer 170", "E75L" to "Embraer 175", "E190" to "Embraer 190",
    "CRJ2" to "Bombardier CRJ200", "CRJ7" to "Bombardier CRJ700", "CRJ9" to "Bombardier CRJ900",
    "C172" to "Cessna 172 (small prop)", "PC12" to "Pilatus PC-12 (turboprop)",
    "GLF4" to "Gulfstream IV (private jet)", "GLF5" to "Gulfstream V (private jet)",
)

/** Human airline name from an ICAO callsign prefix, e.g. UAL328 → "United". */
fun airlineName(callsign: String?): String? {
    if (callsign == null) return null
    val prefix = callsign.trim().takeWhile { it.isLetter() }.uppercase()
    return AIRLINES[prefix]
}

/** Friendly aircraft type, e.g. B738 → "Boeing 737-800"; falls back to the raw code. */
fun friendlyType(type: String?): String? = type?.let { TYPES[it.uppercase()] ?: it }

fun verticalTrend(fpm: Int?): String? = when {
    fpm == null -> null
    fpm > 250 -> "climbing"
    fpm < -250 -> "descending"
    else -> "level"
}
