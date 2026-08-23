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

// ── Callsign matching ────────────────────────────────────────────────────────────────────────
//
// Controllers never say "UAL328" — they say "United three twenty eight". Everything below exists
// to close that gap, so a rule you typed as a flight number matches what is actually spoken.

/** IATA two-letter codes to the ICAO prefixes ADS-B and the catalog use. */
private val IATA_TO_ICAO = mapOf(
    "UA" to "UAL", "AA" to "AAL", "DL" to "DAL", "WN" to "SWA", "B6" to "JBU",
    "NK" to "NKS", "F9" to "FFT", "AS" to "ASA", "G4" to "AAY", "HA" to "HAL",
    "SY" to "SCX", "9E" to "EDV", "YX" to "RPA", "OO" to "SKW", "MQ" to "ENY",
    "AC" to "ACA", "WS" to "WJA", "BA" to "BAW", "LH" to "DLH", "AF" to "AFR",
    "KL" to "KLM", "EK" to "UAE", "VS" to "VIR", "QR" to "QTR", "AM" to "AMX",
    "Y4" to "VOI", "FX" to "FDX", "5X" to "UPS",
)

/** Airline spoken name to ICAO prefix, derived from the table above so the two can't drift. */
private val NAME_TO_ICAO: Map<String, String> by lazy {
    AIRLINES.entries.associate { (icao, name) ->
        name.substringBefore(" (").uppercase() to icao
    }
}

private val UNITS = mapOf(
    "ZERO" to 0, "OH" to 0, "O" to 0, "ONE" to 1, "TWO" to 2, "THREE" to 3, "TREE" to 3,
    "FOUR" to 4, "FIVE" to 5, "FIFE" to 5, "SIX" to 6, "SEVEN" to 7, "EIGHT" to 8,
    "NINE" to 9, "NINER" to 9, "TEN" to 10, "ELEVEN" to 11, "TWELVE" to 12,
    "THIRTEEN" to 13, "FOURTEEN" to 14, "FIFTEEN" to 15, "SIXTEEN" to 16,
    "SEVENTEEN" to 17, "EIGHTEEN" to 18, "NINETEEN" to 19,
)

private val TENS = mapOf(
    "TWENTY" to 20, "THIRTY" to 30, "FORTY" to 40, "FIFTY" to 50,
    "SIXTY" to 60, "SEVENTY" to 70, "EIGHTY" to 80, "NINETY" to 90,
)

private val PHONETIC = mapOf(
    "ALPHA" to "A", "BRAVO" to "B", "CHARLIE" to "C", "DELTA" to "D", "ECHO" to "E",
    "FOXTROT" to "F", "GOLF" to "G", "HOTEL" to "H", "INDIA" to "I", "JULIET" to "J",
    "KILO" to "K", "LIMA" to "L", "MIKE" to "M", "NOVEMBER" to "N", "OSCAR" to "O",
    "PAPA" to "P", "QUEBEC" to "Q", "ROMEO" to "R", "SIERRA" to "S", "TANGO" to "T",
    "UNIFORM" to "U", "VICTOR" to "V", "WHISKEY" to "W", "XRAY" to "X", "X-RAY" to "X",
    "YANKEE" to "Y", "ZULU" to "Z",
)

/**
 * Rewrites spoken numbers into digits: "three twenty eight" → "328", "ten oh six" → "1006",
 * "four fifty" → "450". A tens word followed by a unit merges; everything else concatenates.
 */
private fun digitsFromWords(tokens: List<String>): String {
    val out = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val tens = TENS[token]
        if (tens != null) {
            val next = tokens.getOrNull(i + 1)?.let { UNITS[it] }
            if (next != null && next in 1..9) {
                out.append(tens + next)
                i += 2
                continue
            }
            out.append(tens)
            i++
            continue
        }
        val unit = UNITS[token] ?: return out.toString()
        out.append(unit)
        i++
    }
    return out.toString()
}

/**
 * A transcript with spoken numbers resolved to digits: "Delta four fifty" → "DELTA 450".
 *
 * Phonetics are deliberately left alone here. Several letters of the phonetic alphabet are also
 * ordinary words on the radio — "Delta" above all, which is both the letter D and one of the
 * largest airlines in the country. Collapsing it would turn every Delta callsign into "D450" and
 * quietly stop the airline matching at all. Use [normalizeSpelledOut] when a spelled identifier is
 * what you are after.
 */
fun normalizeTranscript(transcript: String): String = normalize(transcript, collapsePhonetics = false)

/**
 * As [normalizeTranscript], but also folds runs of phonetic letters back into the identifier they
 * spell: "November four two five kilo hotel" → "N 425 KH".
 */
fun normalizeSpelledOut(transcript: String): String = normalize(transcript, collapsePhonetics = true)

private fun normalize(transcript: String, collapsePhonetics: Boolean): String {
    val tokens = transcript.uppercase()
        .replace(Regex("[^A-Z0-9 -]"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    val out = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        when {
            UNITS.containsKey(token) || TENS.containsKey(token) -> {
                var end = i
                while (end < tokens.size && (UNITS.containsKey(tokens[end]) || TENS.containsKey(tokens[end]))) end++
                out.append(digitsFromWords(tokens.subList(i, end)))
                out.append(' ')
                i = end
            }

            collapsePhonetics && PHONETIC.containsKey(token) -> {
                while (i < tokens.size && PHONETIC.containsKey(tokens[i])) {
                    out.append(PHONETIC[tokens[i]])
                    i++
                }
                out.append(' ')
            }

            else -> {
                out.append(token).append(' ')
                i++
            }
        }
    }
    return out.toString().trim()
}

/**
 * Turns whatever the user typed into an ICAO callsign: "UA328", "ual 328", "United 328" all
 * become "UAL328". Tail numbers pass through untouched. Null when it can't be read as a flight.
 */
fun normalizeFlightNumber(input: String): String? {
    val cleaned = input.uppercase().replace(Regex("[^A-Z0-9 ]"), " ").trim()
    if (cleaned.isEmpty()) return null

    // "UNITED 328" / "AMERICAN 1170"
    val byName = NAME_TO_ICAO.entries.firstOrNull { cleaned.startsWith(it.key) }
    if (byName != null) {
        val digits = cleaned.removePrefix(byName.key).filter { it.isDigit() }
        if (digits.isNotEmpty()) return byName.value + digits.trimStart('0').ifEmpty { digits }
    }

    val compact = cleaned.replace(" ", "")
    val match = Regex("^([A-Z]{2,3})(\\d{1,4})$").find(compact) ?: return null
    val (prefix, number) = match.destructured
    val icao = if (prefix.length == 2) IATA_TO_ICAO[prefix] ?: return null else prefix
    return icao + number
}

/**
 * Does this transmission address [icaoCallsign]? Checks the literal callsign, the spoken airline
 * form ("United 328"), and the IATA form, all against a number-and-phonetic-normalised transcript.
 */
fun transcriptMentionsCallsign(transcript: String, icaoCallsign: String): Boolean {
    val target = icaoCallsign.uppercase().replace(" ", "")
    // Both readings are needed: a tail number is spelled phonetically, while an airline name can
    // itself be a phonetic word. Neither normalisation alone catches both.
    val spoken = normalizeTranscript(transcript).replace(" ", "")
    val spelled = normalizeSpelledOut(transcript).replace(" ", "")
    if (target in spoken || target in spelled) return true

    val split = Regex("^([A-Z]{3})(\\d{1,4})$").find(target) ?: return false
    val (prefix, number) = split.destructured

    val spokenName = AIRLINES[prefix]?.substringBefore(" (")?.uppercase()
    if (spokenName != null && "$spokenName$number" in spoken) return true

    val iata = IATA_TO_ICAO.entries.firstOrNull { it.value == prefix }?.key
    return iata != null && ("$iata$number" in spoken || "$iata$number" in spelled)
}
