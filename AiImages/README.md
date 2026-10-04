# Source artwork

Generated images, kept as the originals behind the assets the app actually ships.
Only the six that were used are here; the other twenty-nine variants were
removed once a pick was made.

Nothing in this folder is packaged into the APK. Everything here is a source that
one of the `tools/` scripts turns into something small enough to ship.

## Aircraft silhouettes

Each produces one radar target icon, keyed to an ADS-B emitter category in
`RadarScreen.rememberTargetIcons()`.

| Source | Ships as | Category |
| --- | --- | --- |
| `Light_propeller_aircraft_silhouette_20261004013636.jpg` | `res/drawable-nodpi/ic_ac_light.png` | A1 light |
| `Turboprop_airliner_silhouette_pl…_20261004013556.jpg` | `res/drawable-nodpi/ic_ac_regional.png` | A2 small |
| `Twin_engine_jet_airliner_silhouette_20261004013514.jpg` | `res/drawable-nodpi/ic_ac_narrowbody.png` | A3, A4, A6 |
| `Four-engine_jet_airliner_silhouette_20261004013447.jpg` | `res/drawable-nodpi/ic_ac_widebody.png` | A5 heavy |
| `Helicopter_silhouette_top-down_view_20261004013336.jpg` | `res/drawable-nodpi/ic_ac_rotorcraft.png` | A7 rotorcraft |

To rebuild one:

```sh
python3 tools/import_ai_silhouettes.py --size 256 \
    --out app/src/main/res/drawable-nodpi/ic_ac_narrowbody.png \
    "AiImages/Twin_engine_jet_airliner_silhouette_20261004013514.jpg"
```

`--preview` rasterises the result to the terminal instead of writing it, which is
the only way to check a silhouette in a pipeline that cannot open an image.

The import exists because these are JPEGs. The model was asked for a transparent
background and drew a checkerboard — the picture of transparency rather than the
thing — and JPEG cannot carry alpha regardless. The script thresholds the
aircraft away from the grid, re-centres it so the scope's rotation turns it about
its own centre, mirrors one half onto the other so a generated aircraft's slight
asymmetry doesn't read as bad data, and writes white RGB with the shape in alpha
so `android:tint` can colour it from the altitude ramp.

## Boot clip

`CRT_radar_display_powering_on_20261004013324.mp4` ships as
`res/raw/boot_scope.mp4`.

The original is 10 s, 1280x720 and 1.6 MB. The boot screen lasts 2.2 s and the
app is portrait, so it is trimmed to the 2.4 s holding the whole arc — the dot,
the warm-up bloom, the sweep igniting, the range rings resolving — centre-cropped
square and re-encoded to 79 KB:

```sh
ffmpeg -i "AiImages/CRT_radar_display_powering_on_20261004013324.mp4" -t 2.4 \
    -vf "crop=720:720:(iw-720)/2:(ih-720)/2,scale=640:640" \
    -an -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p -movflags +faststart \
    app/src/main/res/raw/boot_scope.mp4
```

## A line about what belongs here

Decorative chrome only. Nothing generated should ever stand in for information —
no airport diagrams, approach plates, runway layouts or weather imagery. This app
goes to some trouble elsewhere to avoid showing things it cannot actually know,
and a convincing generated airport diagram would undo that.
