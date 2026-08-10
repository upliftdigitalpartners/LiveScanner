# Live Scanner

Stream live **air-traffic control** (LiveATC.net) and **emergency/scanner** feeds (Broadcastify)
on your Android phone — and in the car via **Android Auto**. Pure curiosity fuel: eavesdrop on
the tower while you drive past the airport, or tune the local PD on a road trip.

> Built to be **sideloaded** (developer mode) — no Play Store submission required.
> Native Kotlin + Jetpack Compose + Media3. iOS/CarPlay may come later.

---

## What it does

- **Air Traffic** — 50+ major US airport tower/approach feeds from LiveATC.net, free, no account.
- **Scanner** — add Broadcastify police/fire/EMS feeds by ID, or any direct stream URL.
- **Nearby** — sorts feeds by distance to your location (great on a road trip).
- **Favorites**, **search**, background playback, lock-screen controls.
- **Android Auto** — the same feed tree shows up in the car; tap to listen.
- **Live radar** — listening to an airport? Swipe to **Radar** to watch live aircraft on a navigation-display scope (ADS-B via [adsb.lol](https://adsb.lol/), free, no key): altitude-coloured targets with altitude posts, lead vectors, fading trails, phosphor persistence as the sweep crosses them, a coastline underlay, pinch zoom, drag to re-centre, track-up mode, an inferred approach corridor, a side-profile strip showing the arrival stack, and tap any plane for details + a real photo of it ([planespotters](https://www.planespotters.net/)).
- **COMM 2** — long-press any feed to monitor it quietly underneath the one you're listening to, the way a real radio stack lets you keep an ear on ground while you work tower.
- **Flight recorder** — a rolling 30-minute buffer of the feed, split into transmissions you can re-read, replay and export as a clip. One buffer per feed, and they survive restarts.
- **Track a flight** — arm a rule with a flight number and the app follows it: banner and notification whenever it's addressed, an amber `TRK` ring on the scope, and a one-tap filter in the recorder showing only that aircraft's calls.
- **Alert rules** — keyword, flight, tail-number and feed watches (MAYDAY, GO AROUND, your own N-number) with a banner and a notification the moment one is heard.
- **Audio panel** — comm-radio DSP: gain, squelch gate, EQ voicings, silence trimming, and a red-light **night mode**.
- **AI layer** — live **captions** (Groq Whisper) primed with the tuned airport's own runway and fix names, **plain-English** decoding, **auto-follow** that spotlights the plane being talked to, **anomaly surfacing** that flags the unusual transmission you had no rule for, word-level **tap-to-hear** in the transcript, and **ask the feed** a plain question about the last half hour. Bring your own free [Groq](https://console.groq.com) key. Experimental.

## Flight Deck

The UI is styled as avionics: near-black panels, [B612 Mono](https://en.wikipedia.org/wiki/B612_(typeface))
(the ESA cockpit typeface, bundled as TTFs — never fetched at runtime), and a fixed token palette
in `ui/theme/Color.kt`. Five screens sit on one horizontal filmstrip:

| Screen | What it is |
| --- | --- |
| **Home** | Comm panel — active radio stack, search, feed list, soft keys |
| **Radar** | Navigation display — sweep, traffic, AI decode strip |
| **History** | Flight recorder — one card per transmission, replay + clip export |
| **Alerts** | Armed watch rules and the rule builder |
| **Audio** | Signal-vs-squelch scope, gain/squelch, EQ, night mode |

Two rules run through the whole thing: **all motion stops when the audio is paused** — a scope that
keeps sweeping while nothing is playing is lying about being live — and **night mode is a second
colour scheme**, not a filter, so red-light mode stays legible instead of muddy.

### Where the scope's data comes from

Two things on the navigation display are inferred rather than looked up, because the data isn't
bundled and couldn't be:

**The approach corridor** is derived from the traffic itself. Precise runway thresholds aren't in
the app, but aircraft on final announce the active runway by flying it — low, slowing, descending,
all pointing the same way. The circular mean of their tracks is the approach heading, and it's only
drawn when their spread is tight enough to mean something. That also makes it *live*: it follows a
runway change instead of showing every runway the field has.

**The coastline** is clipped from Natural Earth 1:10m data at build time by `tools/build_coastline.py`
into `assets/coastline.json` — 31 of the 54 airports sit near enough to a shore to get one. Inland
fields simply have no underlay. The Great Lakes aren't included: Natural Earth's coastline layer is
ocean shoreline only, so Chicago, Cleveland, Detroit and Milwaukee currently draw bare.

### Hearing your flight number

Controllers never say "UAL328". They say *"United three twenty eight"*, and tail numbers come out
as *"November four two five kilo hotel"*. So matching a rule against the raw transcript alone
finds nothing.

`data/Aviation.kt` closes that gap on two fronts. Whatever you type is normalised to one ICAO
callsign — `UA328`, `ual 328` and `United 328` all become `UAL328`. Every transcript is then
normalised in the other direction: spoken numbers collapse into digits (*"three twenty eight"* →
`328`, *"ten oh six"* → `1006`, *"four fifty"* → `450`) and the phonetic alphabet folds back into
letters, so `N425KH` is findable in a sentence that never spelled it. Rules also match against the
callsign the transcriber independently resolved, which catches phrasings the text pass misses.

## How it’s built

The whole app is organized around **Media3’s `MediaLibraryService`** — one playback engine that
serves three faces at once:

```
ScannerPlaybackService (MediaLibraryService)
 ├─ ExoPlayer .................. streams MP3/AAC (ICY/Shoutcast) over HTTP(S)
 ├─ MediaLibrarySession ........ browsable tree: Nearby / Air Traffic / Scanner / Favorites / All
 │     ├─ phone UI ............. MediaController + Jetpack Compose
 │     ├─ lock screen / notif .. automatic media notification
 │     └─ Android Auto ......... renders its own UI from the same tree
 └─ FeedRepository ............. bundled catalog (assets/feeds.json) + your custom feeds
```

- **LiveATC** plays from `https://d.liveatc.net/<ident>` (302-redirects to a stream node; HTTPS, no auth).
- **Broadcastify** resolves to `https://audio.broadcastify.com/<id>.mp3`. That endpoint needs a
  **Premium** account — credentials (Settings) are injected as a basic-auth header only for
  `broadcastify.com` hosts. Without Premium, Broadcastify feeds are best-effort; LiveATC is the
  reliable core.

---

## Build & run

You need **Android Studio** (it bundles the right JDK + Gradle + SDK). This project targets
`compileSdk 35` / `minSdk 26`.

### Option A — Android Studio (recommended)
1. **Open** `~/Repo/LiveScanner` in Android Studio. Let it sync Gradle.
2. Plug in your phone (USB debugging on) or start an emulator.
3. Press **Run ▶** (the `app` config).

### Option B — Command line
The system Java is too old for Gradle, so point `JAVA_HOME` at Android Studio’s bundled JDK:

```bash
cd ~/Repo/LiveScanner
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # build + install to the connected device
```

### Sideload the APK to a phone
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or copy the `.apk` to the phone and open it (enable “Install unknown apps” for your file manager).

### Share it with friends (signed release)
To share — and let friends install **updates** over old versions — build the **release** APK, which is
signed with a stable key. The key lives in a **gitignored** `keystore/livescanner-release.jks`, with its
passwords in a **gitignored** `keystore.properties`. If both are present, `assembleRelease` signs
automatically; if they're missing (e.g. a fresh clone) the release build still succeeds but stays unsigned.

Re-create the key if needed, then build:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# (only if keystore/ is missing)
"$JAVA_HOME/bin/keytool" -genkeypair -v -keystore keystore/livescanner-release.jks \
  -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias livescanner \
  -dname "CN=Live Scanner, O=Personal, C=US"
#   ...then write keystore.properties with storeFile/storePassword/keyAlias/keyPassword

./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk (signed)
```
Send friends that `app-release.apk` (Google Drive/Dropbox link, Telegram, or a GitHub Release — not
AirDrop) along with **[INSTALL.md](INSTALL.md)**. **Back up `keystore/` + `keystore.properties`** — lose
the key and you can't publish updates that install over the old app.

---

## Android Auto (sideloaded apps)

Android Auto **won’t show a non-Play-Store media app until you allow unknown sources**. One-time setup
on the phone:

1. Make sure **Android Auto** is installed/updated.
2. Open its settings: **Settings → Apps → Android Auto → (open the app’s additional settings)**.
3. Scroll to **“Version”** and tap it ~**10 times** to unlock **Developer mode**.
4. Open the **⋮ menu → Developer settings** and enable **“Unknown sources.”**
5. Back in Android Auto settings, open **“Customize launcher”** and tick **Live Scanner**.

> Menu wording shifts between Android Auto versions, but the toggle is always **Developer settings →
> Unknown sources**.

### What the car surface can and can't be

Worth being straight about this, because the Flight Deck design mocks a full custom head-unit
screen. **Android Auto media apps do not draw their own UI.** The app hands the system
`MediaItem`s and Google's Media Template renders them — there is no hook for a custom rail, a
glance radar scope, a caption bar or a heads-up alert card. The Car App Library (`androidx.car.app`)
doesn't close the gap either: its templates cover navigation, POI, parking and charging, not media,
and free-form surface drawing is limited to navigation-category apps.

So what the car actually gets is everything reachable through content: three segments
(Favorites / Air Traffic / Scanner), **at most six items per list**, a grid layout so every target
clears the 72dp minimum, nearest-first ordering, and the feed code plus frequency in each card's
title. That's `playback/MediaItemTree.kt` in full. A true Flight Deck head-unit screen would mean
targeting **Android Automotive OS**, where the app owns the display — a different build and a
different distribution story.

### Test it without a car — Desktop Head Unit (DHU)
1. In **Android Studio → SDK Manager → SDK Tools**, install **“Android Auto Desktop Head Unit emulator.”**
2. In Android Auto **Developer settings**, enable **“Start head unit server.”**
3. Connect the phone by USB and run:
   ```bash
   ~/Library/Android/sdk/extras/google/auto/desktop-head-unit
   ```
4. The DHU window opens; pick **Live Scanner** from the media apps. Or just plug into a car that has
   Android Auto.

---

## Feeds

### Bundled (LiveATC)
`app/src/main/assets/feeds.json` ships 50+ verified airport feeds. To **add or refresh** them, use the
helper that probes LiveATC for working stream idents:

```bash
bash tools/probe_liveatc.sh        # writes /tmp/liveatc_feeds.json (edit the airport list inside)
```
Idents drift over time. To check/find one manually, a feed’s playlist lives at
`https://www.liveatc.net/play/<ident>.pls` — the `File1=` line is the stream URL.

### Add your own (in-app)
Tap **＋ Add feed**:
- **Direct URL** — paste any `http(s)` MP3/AAC stream (a custom Icecast feed, a police dept stream, etc.).
- **Broadcastify ID** — the number from `broadcastify.com/listen/feed/<ID>`. For reliable audio, add
  your **Broadcastify Premium** username/password under **Settings**.

---

## Legal / fair use

This is a **personal-use** listener, not a redistribution service. Respect
[LiveATC](https://www.liveatc.net/) and [Broadcastify](https://www.broadcastify.com/) Terms of Service
— don’t rebroadcast their streams or use them commercially. Listening to ATC is legal in the US; **mobile
use of police scanners is restricted in some states/countries** — know your local laws before using
scanner feeds in a vehicle.

## Roadmap
- **TTS decode** — speak the plain-English aloud for the car; spoken-callsign tuning; a jargon glossary.
- Radar: optional map underlay, altitude filters, pinch-zoom, weather overlay.
- Sleep timer, recent history; iOS + CarPlay (separate native target — catalog + ADS-B layers are portable).
