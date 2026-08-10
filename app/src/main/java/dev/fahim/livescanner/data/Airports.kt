package dev.fahim.livescanner.data

/**
 * Per-field vocabulary for Whisper prompt priming.
 *
 * Whisper biases decoding toward words it sees in the prompt, so feeding it the runway numbers,
 * fixes, navaids and landmarks a controller at that field actually says is what stops local place
 * names coming back as nonsense. Entries are deliberately short and deliberately real: an invented
 * waypoint pushes the decoder the wrong way, so fields we are unsure about carry runways and the
 * spoken airport name only.
 */

/** Generic ATC phraseology priming, used alone when the field is unknown. */
internal const val ATC_PROMPT =
    "Air traffic control radio. Aviation phraseology: callsigns, runways, headings, " +
        "altitudes, cleared for takeoff, cleared to land, contact, traffic, squawk."

private val LOCAL_TERMS: Map<String, String> = mapOf(
    "KJFK" to "Kennedy. Runways 4 Left, 4 Right, 22 Left, 22 Right, 13 Left, 13 Right, " +
        "31 Left, 31 Right. Canarsie, Kennedy VOR, CAMRN, ROBER, LENDY, DEEZZ, SKORR, " +
        "Belt Parkway, Jamaica Bay.",
    "KLGA" to "LaGuardia. Runways 4, 22, 13, 31. Expressway Visual, River Visual, " +
        "Whitestone Bridge, Flushing Bay, Rikers Island, LaGuardia VOR, Kennedy, Newark.",
    "KEWR" to "Newark Liberty. Runways 4 Left, 4 Right, 22 Left, 22 Right, 11, 29. " +
        "Colts Neck, Solberg, Stillwater, Teterboro, Newark Bay, Elizabeth, Verrazano.",
    "KBOS" to "Boston Logan. Runways 4 Left, 4 Right, 22 Left, 22 Right, 27, 9, 15 Right, " +
        "33 Left, 14, 32. Boston VOR, SCUPP, ROBUC, Deer Island, Boston Harbor, Hanscom, Quincy.",
    "KPHL" to "Philadelphia. Runways 9 Left, 9 Right, 27 Left, 27 Right, 17, 35, 8, 26. " +
        "Modena, DuPont, Woodstown, Yardley, Delaware River, Northeast Philadelphia, Wilmington.",
    "KDCA" to "Reagan National. Runways 1, 19, 15, 33, 4, 22. River Visual, Potomac River, " +
        "Mount Vernon, Rosslyn, Wilson Bridge, Andrews, Washington VOR.",
    "KIAD" to "Dulles. Runways 1 Left, 1 Center, 1 Right, 19 Left, 19 Center, 19 Right, 12, 30. " +
        "Armel, Casanova, Linden, Front Royal, Leesburg, Manassas, Potomac.",
    "KBWI" to "Baltimore Washington. Runways 10, 28, 15 Left, 15 Right, 33 Left, 33 Right. " +
        "Baltimore VOR, Chesapeake Bay, Patapsco River, Key Bridge, Martin State, Andrews.",
    "KBDL" to "Bradley. Runways 6, 24, 1, 19. Windsor Locks, Hartford, Connecticut River, " +
        "Springfield, Barnes.",
    "KPIT" to "Pittsburgh. Runways 10 Left, 10 Center, 10 Right, 28 Left, 28 Center, 28 Right, " +
        "14, 32. Allegheny County, Ohio River, Monongahela, Beaver.",
    "KATL" to "Hartsfield Jackson. Runways 8 Left, 8 Right, 9 Left, 9 Right, 26 Left, 26 Right, " +
        "27 Left, 27 Right, 10, 28. Atlanta VOR, Macon, Rome, Athens, LaGrange, " +
        "Peachtree DeKalb, Fulton County.",
    "KCLT" to "Charlotte Douglas. Runways 18 Left, 18 Center, 18 Right, 36 Left, 36 Center, " +
        "36 Right, 5, 23. Charlotte VOR, Concord, Gastonia, Fort Mill, Catawba River, Rock Hill.",
    "KMCO" to "Orlando International. Runways 17 Left, 17 Right, 18 Left, 18 Right, 35 Left, " +
        "35 Right, 36 Left, 36 Right. Orlando VOR, Orlando Executive, Kissimmee, Sanford, " +
        "Lakeland, Melbourne, Cape Canaveral.",
    "KMIA" to "Miami International. Runways 8 Left, 8 Right, 26 Left, 26 Right, 9, 27, 12, 30. " +
        "Dolphin, Virginia Key, Biscayne Bay, Opa Locka, Miami Beach, Everglades, Tamiami.",
    "KFLL" to "Fort Lauderdale Hollywood. Runways 10 Left, 10 Right, 28 Left, 28 Right. " +
        "Fort Lauderdale Executive, Pompano Beach, Hollywood, Dania, Port Everglades, Miami.",
    "KTPA" to "Tampa International. Runways 1 Left, 1 Right, 19 Left, 19 Right, 10, 28. " +
        "Tampa Bay, MacDill, Saint Petersburg Clearwater, Sarasota, Lakeland, Skyway Bridge.",
    "KRDU" to "Raleigh Durham. Runways 5 Left, 5 Right, 23 Left, 23 Right, 14, 32. " +
        "Raleigh Durham VOR, Research Triangle, Chapel Hill, Falls Lake, Johnston County.",
    "KBNA" to "Nashville International. Runways 2 Left, 2 Center, 2 Right, 20 Left, 20 Center, " +
        "20 Right, 13, 31. Nashville VOR, Cumberland River, Smyrna, Murfreesboro, John C Tune.",
    "KMEM" to "Memphis International. Runways 18 Left, 18 Center, 18 Right, 36 Left, 36 Center, " +
        "36 Right, 9, 27. Memphis VOR, Mississippi River, Millington, Olive Branch, West Memphis.",
    "KORD" to "Chicago O'Hare. Runways 9 Left, 9 Center, 9 Right, 27 Left, 27 Center, 27 Right, " +
        "10 Left, 10 Center, 10 Right, 28 Left, 28 Center, 28 Right, 4 Left, 4 Right, 22 Left, " +
        "22 Right. Northbrook, Joliet, Chicago Heights, DuPage, Lake Michigan, Midway.",
    "KMDW" to "Chicago Midway. Runways 4 Left, 4 Right, 22 Left, 22 Right, 13 Left, 13 Center, " +
        "13 Right, 31 Left, 31 Center, 31 Right. Lake Michigan, O'Hare, Joliet, " +
        "Chicago Heights, Gary.",
    "KDTW" to "Detroit Metropolitan. Runways 3 Left, 3 Right, 4 Left, 4 Right, 9 Left, 9 Right, " +
        "21 Left, 21 Right, 22 Left, 22 Right, 27 Left, 27 Right. Salem, Carleton, Willow Run, " +
        "Ann Arbor, Windsor, Lake Erie.",
    "KMSP" to "Minneapolis Saint Paul. Runways 12 Left, 12 Right, 30 Left, 30 Right, 17, 35, " +
        "4, 22. Gopher, Farmington, Flying Cloud, Saint Paul Downtown, Mississippi River, " +
        "Lake Minnetonka.",
    "KSTL" to "Lambert Saint Louis. Runways 11, 29, 12 Left, 12 Right, 30 Left, 30 Right. " +
        "Foristell, Spirit of Saint Louis, Mississippi River, Missouri River, Alton.",
    "KMCI" to "Kansas City International. Runways 1 Left, 1 Right, 19 Left, 19 Right. " +
        "Napoleon, Charles B Wheeler Downtown, Missouri River, Johnson County.",
    "KIND" to "Indianapolis International. Runways 5 Left, 5 Right, 23 Left, 23 Right, 14, 32. " +
        "Brickyard, Shelbyville, Speedway, Eagle Creek, Mount Comfort, White River.",
    "KCLE" to "Cleveland Hopkins. Runways 6 Left, 6 Right, 24 Left, 24 Right, 10, 28. " +
        "Dryer, Lake Erie, Burke Lakefront, Cuyahoga County, Akron Canton.",
    "KCMH" to "John Glenn Columbus. Runways 10 Left, 10 Right, 28 Left, 28 Right. " +
        "Appleton, Rickenbacker, Ohio State University, Bolton Field, Scioto River.",
    "KMKE" to "General Mitchell Milwaukee. Runways 1 Left, 1 Right, 19 Left, 19 Right, " +
        "7 Right, 25 Left. Badger, Lake Michigan, Timmerman, Racine, Kenosha.",
    "KDFW" to "Dallas Fort Worth. Runways 17 Left, 17 Center, 17 Right, 35 Left, 35 Center, " +
        "35 Right, 18 Left, 18 Right, 36 Left, 36 Right, 13 Left, 13 Right, 31 Left, 31 Right. " +
        "Love Field, Alliance, Meacham, Grapevine Lake, Bonham.",
    "KDAL" to "Dallas Love Field. Runways 13 Left, 13 Right, 31 Left, 31 Right, 18, 36. " +
        "Downtown Dallas, Dallas Fort Worth, Addison, Dallas Executive, White Rock Lake.",
    "KIAH" to "George Bush Intercontinental. Runways 8 Left, 8 Right, 26 Left, 26 Right, 9, 27, " +
        "15 Left, 15 Right, 33 Left, 33 Right. Humble, Hobby, Ellington, Conroe, " +
        "Lake Houston, Sugar Land.",
    "KHOU" to "Houston Hobby. Runways 4, 22, 13 Right, 31 Left, 17, 35. Ellington, " +
        "Intercontinental, Galveston, Clear Lake, Pearland, Sugar Land.",
    "KAUS" to "Austin Bergstrom. Runways 18 Left, 18 Right, 36 Left, 36 Right. " +
        "Austin Executive, Georgetown, San Marcos, Lake Travis, Colorado River.",
    "KSAT" to "San Antonio International. Runways 12 Left, 12 Right, 30 Left, 30 Right, 4, 22. " +
        "Randolph, Lackland, Kelly Field, Stinson, Boerne, New Braunfels.",
    "KMSY" to "Louis Armstrong New Orleans. Runways 11, 29, 20. Lake Pontchartrain, " +
        "New Orleans Lakefront, Mississippi River, Harvey, Kenner.",
    "KDEN" to "Denver International. Runways 16 Left, 16 Right, 34 Left, 34 Right, 17 Left, " +
        "17 Right, 35 Left, 35 Right, 7, 25, 8, 26. Falcon, Kiowa, Buckley, " +
        "Rocky Mountain Metropolitan, Front Range, Centennial.",
    "KSLC" to "Salt Lake City International. Runways 16 Left, 16 Right, 34 Left, 34 Right, " +
        "17, 35, 14, 32. Fairfield, Provo, Ogden, Great Salt Lake, Wasatch, Tooele.",
    "KPHX" to "Phoenix Sky Harbor. Runways 7 Left, 7 Right, 25 Left, 25 Right, 8, 26. " +
        "Phoenix VOR, Deer Valley, Scottsdale, Chandler, Luke, Salt River.",
    "KLAS" to "Harry Reid Las Vegas. Runways 1 Left, 1 Right, 19 Left, 19 Right, 8 Left, " +
        "8 Right, 26 Left, 26 Right. Nellis, North Las Vegas, Henderson, Boulder City, " +
        "Lake Mead, the Strip.",
    "KABQ" to "Albuquerque Sunport. Runways 8, 26, 3, 21, 12, 30. Sandia Mountains, " +
        "Rio Grande, Kirtland, Double Eagle, Santa Fe, Belen.",
    "KLAX" to "Los Angeles International. Runways 24 Left, 24 Right, 6 Left, 6 Right, 25 Left, " +
        "25 Right, 7 Left, 7 Right. SADDE, Seal Beach, Santa Catalina, Ventura, Santa Monica, " +
        "Hawthorne, Downey.",
    "KSFO" to "San Francisco International. Runways 28 Left, 28 Right, 10 Left, 10 Right, " +
        "1 Left, 1 Right, 19 Left, 19 Right. Woodside, SERFR, Quiet Bridge Visual, " +
        "San Mateo Bridge, Bay Bridge, Point Reyes, Oakland.",
    "KSAN" to "San Diego International. Runways 9, 27. Lindbergh Field, Point Loma, " +
        "Mission Bay, Coronado, North Island, Miramar, Montgomery Field, Gillespie.",
    "KSEA" to "Seattle Tacoma. Runways 16 Left, 16 Center, 16 Right, 34 Left, 34 Center, " +
        "34 Right. Boeing Field, Paine Field, Renton, Puget Sound, Olympia, Mount Rainier.",
    "KPDX" to "Portland International. Runways 10 Left, 10 Right, 28 Left, 28 Right, 3, 21. " +
        "Battle Ground, Columbia River, Willamette, Hillsboro, Troutdale, Mount Hood, Vancouver.",
    "KSJC" to "Norman Mineta San Jose. Runways 12 Left, 12 Right, 30 Left, 30 Right. " +
        "Reid Hillview, Moffett Field, Palo Alto, Santa Clara, San Francisco Bay.",
    "KOAK" to "Oakland International. Runways 12, 30, 10 Left, 10 Right, 28 Left, 28 Right. " +
        "Oakland VOR, Bay Bridge, Alameda, San Leandro, Hayward, Berkeley.",
    "KSMF" to "Sacramento International. Runways 16 Left, 16 Right, 34 Left, 34 Right. " +
        "Sacramento Executive, Mather, McClellan, Sacramento River, Davis.",
    "KSNA" to "John Wayne Orange County. Runways 20 Left, 20 Right, 2 Left, 2 Right. " +
        "Newport Beach, Santa Ana, Seal Beach, Long Beach, Fullerton, Santa Catalina.",
    "KTEB" to "Teterboro. Runways 6, 24, 1, 19. Sparta, Hackensack River, Hudson River, " +
        "Morristown, Caldwell, Newark, LaGuardia.",
    "KSDF" to "Louisville Muhammad Ali International. Runways 17 Left, 17 Right, 35 Left, " +
        "35 Right. Standiford Field, Bowman Field, Ohio River, Worldport, Fort Knox.",
    "KVNY" to "Van Nuys. Runways 16 Left, 16 Right, 34 Left, 34 Right. Fillmore, Burbank, " +
        "San Fernando Valley, Whiteman, Santa Monica Mountains, Santa Clarita.",
    "KMRY" to "Monterey. Runways 10 Left, 10 Right, 28 Left, 28 Right. Monterey Bay, Salinas, " +
        "Carmel, Big Sur, Point Pinos, Marina, Watsonville.",
    "KASE" to "Aspen Pitkin County. Runways 15, 33. Sardy Field, Red Table, Roaring Fork Valley, " +
        "Glenwood Springs, Rifle, Independence Pass.",
)

/** Terms a controller at [icao] actually says, used to bias Whisper decoding. */
fun airportPrompt(icao: String?): String {
    val local = icao?.trim()?.uppercase()?.let { LOCAL_TERMS[it] } ?: return ATC_PROMPT
    return "$ATC_PROMPT Local terms: $local"
}
