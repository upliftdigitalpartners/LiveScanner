#!/usr/bin/env bash
# Probe LiveATC for working tower (fallback approach/ground) feed idents at major
# US airports and emit a JSON array of verified feeds. Only idents whose .pls
# endpoint returns 200 with a File1 stream line are included.
UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
OUT=/tmp/liveatc_feeds.json

# ICAO|Short name|City, ST|lat|lon|Full name
AIRPORTS=$(cat <<'EOF'
KJFK|JFK|New York, NY|40.6413|-73.7781|John F. Kennedy Intl
KLGA|LaGuardia|New York, NY|40.7769|-73.8740|LaGuardia
KEWR|Newark|Newark, NJ|40.6895|-74.1745|Newark Liberty Intl
KBOS|Boston Logan|Boston, MA|42.3656|-71.0096|Boston Logan Intl
KPHL|Philadelphia|Philadelphia, PA|39.8744|-75.2424|Philadelphia Intl
KDCA|Reagan National|Washington, DC|38.8512|-77.0402|Reagan Washington National
KIAD|Dulles|Washington, DC|38.9531|-77.4565|Washington Dulles Intl
KBWI|BWI|Baltimore, MD|39.1754|-76.6683|Baltimore/Washington Intl
KBDL|Bradley|Hartford, CT|41.9389|-72.6832|Bradley Intl
KPIT|Pittsburgh|Pittsburgh, PA|40.4915|-80.2329|Pittsburgh Intl
KATL|Atlanta|Atlanta, GA|33.6407|-84.4277|Hartsfield-Jackson Atlanta Intl
KCLT|Charlotte|Charlotte, NC|35.2140|-80.9431|Charlotte Douglas Intl
KMCO|Orlando|Orlando, FL|28.4312|-81.3081|Orlando Intl
KMIA|Miami|Miami, FL|25.7959|-80.2870|Miami Intl
KFLL|Fort Lauderdale|Fort Lauderdale, FL|26.0742|-80.1506|Fort Lauderdale-Hollywood Intl
KTPA|Tampa|Tampa, FL|27.9755|-82.5332|Tampa Intl
KRDU|Raleigh-Durham|Raleigh, NC|35.8801|-78.7880|Raleigh-Durham Intl
KBNA|Nashville|Nashville, TN|36.1245|-86.6782|Nashville Intl
KMEM|Memphis|Memphis, TN|35.0424|-89.9767|Memphis Intl
KORD|O'Hare|Chicago, IL|41.9742|-87.9073|Chicago O'Hare Intl
KMDW|Midway|Chicago, IL|41.7868|-87.7522|Chicago Midway Intl
KDTW|Detroit|Detroit, MI|42.2162|-83.3554|Detroit Metro Wayne County
KMSP|Minneapolis|Minneapolis, MN|44.8848|-93.2223|Minneapolis-St. Paul Intl
KSTL|St. Louis|St. Louis, MO|38.7487|-90.3700|St. Louis Lambert Intl
KMCI|Kansas City|Kansas City, MO|39.2976|-94.7139|Kansas City Intl
KIND|Indianapolis|Indianapolis, IN|39.7173|-86.2944|Indianapolis Intl
KCLE|Cleveland|Cleveland, OH|41.4117|-81.8498|Cleveland Hopkins Intl
KCMH|Columbus|Columbus, OH|39.9980|-82.8919|John Glenn Columbus Intl
KMKE|Milwaukee|Milwaukee, WI|42.9472|-87.8966|Milwaukee Mitchell Intl
KDFW|DFW|Dallas, TX|32.8998|-97.0403|Dallas/Fort Worth Intl
KDAL|Dallas Love|Dallas, TX|32.8471|-96.8518|Dallas Love Field
KIAH|Houston Bush|Houston, TX|29.9902|-95.3368|George Bush Intercontinental
KHOU|Houston Hobby|Houston, TX|29.6454|-95.2789|William P. Hobby
KAUS|Austin|Austin, TX|30.1975|-97.6664|Austin-Bergstrom Intl
KSAT|San Antonio|San Antonio, TX|29.5337|-98.4698|San Antonio Intl
KMSY|New Orleans|New Orleans, LA|29.9934|-90.2580|Louis Armstrong New Orleans Intl
KDEN|Denver|Denver, CO|39.8561|-104.6737|Denver Intl
KSLC|Salt Lake City|Salt Lake City, UT|40.7884|-111.9778|Salt Lake City Intl
KPHX|Phoenix|Phoenix, AZ|33.4342|-112.0116|Phoenix Sky Harbor Intl
KLAS|Las Vegas|Las Vegas, NV|36.0840|-115.1537|Harry Reid Intl
KABQ|Albuquerque|Albuquerque, NM|35.0402|-106.6092|Albuquerque Intl Sunport
KLAX|LAX|Los Angeles, CA|33.9416|-118.4085|Los Angeles Intl
KSFO|SFO|San Francisco, CA|37.6213|-122.3790|San Francisco Intl
KSAN|San Diego|San Diego, CA|32.7338|-117.1933|San Diego Intl
KSEA|Seattle-Tacoma|Seattle, WA|47.4502|-122.3088|Seattle-Tacoma Intl
KPDX|Portland|Portland, OR|45.5898|-122.5951|Portland Intl
KSJC|San Jose|San Jose, CA|37.3639|-121.9289|San Jose Intl
KOAK|Oakland|Oakland, CA|37.7126|-122.2197|Oakland Intl
KSMF|Sacramento|Sacramento, CA|38.6951|-121.5908|Sacramento Intl
KSNA|John Wayne|Santa Ana, CA|33.6757|-117.8682|John Wayne (Orange County)
EOF
)

facility_label() {
  case "$1" in
    *_gnd_twr) echo "Ground/Tower" ;;
    *_twr*) echo "Tower" ;;
    *_app*) echo "Approach" ;;
    *_del*) echo "Clearance" ;;
    *) echo "ATC" ;;
  esac
}

echo "[" > "$OUT"
first=1
ok=0; miss=0
while IFS='|' read -r icao short city lat lon full; do
  [ -z "$icao" ] && continue
  l=$(echo "$icao" | tr 'A-Z' 'a-z')
  found=""
  for cand in "${l}_twr" "${l}_app" "${l}_gnd_twr" "${l}1_twr" "${l}" "${l}1"; do
    code=$(curl -s -m 8 -o /tmp/_p.pls -w "%{http_code}" -A "$UA" "https://www.liveatc.net/play/${cand}.pls")
    if [ "$code" = "200" ] && grep -qiE '^File1=http' /tmp/_p.pls; then
      found="$cand"; break
    fi
  done
  if [ -z "$found" ]; then
    echo "  MISS  $icao ($short)" >&2; miss=$((miss+1)); continue
  fi
  fac=$(facility_label "$found")
  echo "  OK    $icao -> $found [$fac]" >&2; ok=$((ok+1))
  [ $first -eq 0 ] && echo "," >> "$OUT"
  first=0
  cat >> "$OUT" <<JSON
  {
    "id": "liveatc:${found}",
    "name": "${short} ${fac}",
    "source": "LIVEATC",
    "type": "ATC",
    "streamUrl": "https://d.liveatc.net/${found}",
    "location": "${city}",
    "region": "United States",
    "lat": ${lat},
    "lon": ${lon},
    "description": "${full} — ${fac}"
  }
JSON
done <<< "$AIRPORTS"
echo "]" >> "$OUT"
echo "DONE: $ok verified, $miss missed" >&2