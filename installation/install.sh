#!/usr/bin/env bash
# ======================================================================
#  HomeX auf Debian installieren (Proxmox-Container oder VM)
#
#  Als root ausführen:
#    bash install.sh
#
#  Was passiert:
#   1. Pakete (git, maven, tmux …) und Java 25 (Eclipse Temurin) installieren
#   2. HomeX von GitHub holen und bauen
#   3. HomeX nach /opt/homex/server kopieren, Benutzer "homex" anlegen
#   4. Autostart einrichten (systemd) – HomeX läuft in einer tmux-Konsole
#   5. Den Befehl "homex" einrichten (Konsole, Log, Update …)
#
#  Nochmal ausführen ist ungefährlich: Einstellungen bleiben erhalten.
# ======================================================================
set -euo pipefail

REPO="${HOMEX_REPO:-https://github.com/IGemiXI/HomeX.git}"
BRANCH="${HOMEX_BRANCH:-main}"
BASE=/opt/homex
SRC="$BASE/src"
SERVER="$BASE/server"
JAVA_DIR="$BASE/java"
NUTZER=homex

gruen() { printf '\033[32m%s\033[0m\n' "$*"; }
gelb()  { printf '\033[33m%s\033[0m\n' "$*"; }
rot()   { printf '\033[31m%s\033[0m\n' "$*" >&2; }
schritt() { printf '\n\033[1;35m▶ %s\033[0m\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { rot "Bitte als root ausführen (z. B. in der Proxmox-Konsole des Containers)."; exit 1; }
[ -f /etc/debian_version ] || { rot "Dieses Skript ist für Debian gedacht."; exit 1; }

# ---------------------------------------------------------------- 1. Pakete
schritt "Pakete installieren"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends ca-certificates curl git tmux maven jq logrotate locales >/dev/null
gruen "OK"

# Deutsche Umlaute in der Konsole
if ! locale -a 2>/dev/null | grep -qi '^c.utf-\?8$'; then
  gelb "Hinweis: C.UTF-8 fehlt – Umlaute in der Konsole könnten falsch aussehen."
fi

# Zeitzone: Proxmox-Vorlagen stehen auf UTC – Regeln wie "um 06:30" brauchen die echte Ortszeit
if [ "$(readlink -f /etc/localtime 2>/dev/null)" != "/usr/share/zoneinfo/${HOMEX_ZEITZONE:-Europe/Berlin}" ]; then
  ln -sf "/usr/share/zoneinfo/${HOMEX_ZEITZONE:-Europe/Berlin}" /etc/localtime
  echo "${HOMEX_ZEITZONE:-Europe/Berlin}" > /etc/timezone
  gruen "Zeitzone auf ${HOMEX_ZEITZONE:-Europe/Berlin} gestellt"
fi

# ---------------------------------------------------------------- 2. Java 25
schritt "Java 25 (Eclipse Temurin)"
case "$(uname -m)" in
  x86_64)  ARCH=x64 ;;
  aarch64) ARCH=aarch64 ;;
  *) rot "Unbekannte CPU-Architektur $(uname -m)"; exit 1 ;;
esac
if [ -x "$JAVA_DIR/bin/java" ] && "$JAVA_DIR/bin/java" -version 2>&1 | grep -q 'version "25'; then
  gruen "schon installiert: $("$JAVA_DIR/bin/java" -version 2>&1 | head -1)"
else
  INFO=$(curl -fsSL "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$ARCH&image_type=jdk&os=linux&vendor=eclipse")
  LINK=$(jq -r '.[0].binary.package.link' <<<"$INFO")
  SUMME=$(jq -r '.[0].binary.package.checksum' <<<"$INFO")
  NAME=$(jq -r '.[0].release_name' <<<"$INFO")
  [ -n "$LINK" ] && [ "$LINK" != null ] || { rot "Konnte Java 25 bei Adoptium nicht finden."; exit 1; }
  echo "Lade $NAME …"
  TMP=$(mktemp -d)
  curl -fsSL -o "$TMP/jdk.tar.gz" "$LINK"
  echo "$SUMME  $TMP/jdk.tar.gz" | sha256sum -c --quiet - || { rot "Prüfsumme von Java stimmt nicht – Abbruch."; exit 1; }
  rm -rf "$JAVA_DIR" && mkdir -p "$JAVA_DIR"
  tar -xzf "$TMP/jdk.tar.gz" -C "$JAVA_DIR" --strip-components=1
  rm -rf "$TMP"
  gruen "installiert: $NAME"
fi
export JAVA_HOME="$JAVA_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------------------------------------------------------------- 3. Quellcode + Bauen
schritt "HomeX von GitHub holen"
mkdir -p "$BASE"
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" fetch -q origin "$BRANCH"
  git -C "$SRC" reset -q --hard "origin/$BRANCH"
else
  if ! git clone -q --branch "$BRANCH" "$REPO" "$SRC"; then
    rot "Konnte $REPO nicht holen."
    rot "Ist das Repo privat? Dann so starten:  HOMEX_REPO=https://<github-token>@github.com/IGemiXI/HomeX.git bash install.sh"
    exit 1
  fi
fi
gruen "Stand: $(git -C "$SRC" log -1 --format='%h %s' | cut -c1-90)"

schritt "HomeX bauen (beim ersten Mal lädt Maven einige Minuten lang Bibliotheken)"
( cd "$SRC" && mvn -q -B -DskipTests clean package )
gruen "OK"

# ---------------------------------------------------------------- 4. Benutzer + Dateien
schritt "HomeX einrichten in $SERVER"
id "$NUTZER" >/dev/null 2>&1 || useradd --system --home-dir "$BASE" --shell /usr/sbin/nologin "$NUTZER"
mkdir -p "$SERVER/plugins" "$SERVER/plugins/deaktiviert" "$SERVER/logs"
install -m 644 "$SRC/homex-core/target/HomeX.jar" "$SERVER/HomeX.jar"
for jar in "$SRC"/homex-plugins/*/target/*.jar; do
  name=$(basename "$jar")
  # Test-Geräte nur auf Wunsch (HOMEX_TESTGERAETE=1), sonst auch alte Kopien entfernen
  if [ "$name" = "DummyGeraete.jar" ] && [ "${HOMEX_TESTGERAETE:-0}" != "1" ]; then
    rm -f "$SERVER/plugins/$name" "$SERVER/plugins/deaktiviert/$name"
    continue
  fi
  if [ -f "$SERVER/plugins/deaktiviert/$name" ]; then
    install -m 644 "$jar" "$SERVER/plugins/deaktiviert/$name"     # bleibt aus, wird aber aktualisiert
    echo "  $name (deaktiviert, aktualisiert)"
  else
    install -m 644 "$jar" "$SERVER/plugins/$name"
    echo "  $name"
  fi
done

# homex.properties beim ersten Mal mit Fragen anlegen
if [ ! -f "$SERVER/homex.properties" ]; then
  echo
  HAUS="${HOMEX_HAUS:-}"; BREITE="${HOMEX_BREITE:-}"; LAENGE="${HOMEX_LAENGE:-}"
  if [ -t 0 ]; then
    read -rp "Name deines Zuhauses [Mein Zuhause]: " HAUS
    echo "Standort für Wetter und Sonnenzeiten (Google Maps: Rechtsklick aufs Haus → erste Zeile). Leer = später."
    read -rp "  Breitengrad (z. B. 49.21): " BREITE
    read -rp "  Längengrad  (z. B. 7.39):  " LAENGE
  fi
  {
    echo "# HomeX – zentrale Einstellungen (Änderungen gelten nach: homex neustart)"
    echo "haus.name=${HAUS:-Mein Zuhause}"
    echo "standort.breitengrad=${BREITE//,/.}"
    echo "standort.laengengrad=${LAENGE//,/.}"
  } > "$SERVER/homex.properties"
fi

# Startskript: läuft in tmux, startet HomeX nach einem Absturz neu, schreibt ein Log
cat > "$BASE/start.sh" <<'START'
#!/usr/bin/env bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8
cd /opt/homex/server || exit 1
while true; do
  /opt/homex/java/bin/java -Xms64m -Xmx512m -Duser.timezone="${HOMEX_ZEITZONE:-Europe/Berlin}" \
    -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -jar HomeX.jar 2>&1 \
    | tee -a logs/homex.log
  rc=${PIPESTATUS[0]}
  [ "$rc" -eq 0 ] && break                      # sauber mit "stop" beendet
  echo "[HomeX abgestürzt (Code $rc) – Neustart in 10 Sekunden …]" | tee -a logs/homex.log
  sleep 10
done
START
# Stoppskript: "stop" in die Konsole tippen und warten, bis HomeX beendet ist
cat > "$BASE/stop.sh" <<'STOP'
#!/usr/bin/env bash
T="tmux -L homex"
$T has-session -t homex 2>/dev/null || exit 0
$T send-keys -t homex stop Enter
for _ in $(seq 1 25); do
  sleep 1
  $T has-session -t homex 2>/dev/null || exit 0
done
$T kill-session -t homex 2>/dev/null || true
STOP
chmod 755 "$BASE/start.sh" "$BASE/stop.sh"
chown -R "$NUTZER:$NUTZER" "$SERVER"

# ---------------------------------------------------------------- 5. Autostart
schritt "Autostart (systemd) einrichten"
cat > /etc/systemd/system/homex.service <<UNIT
[Unit]
Description=HomeX Smarthome
After=network-online.target
Wants=network-online.target

[Service]
Type=forking
User=$NUTZER
WorkingDirectory=$SERVER
# tmux startet Befehle über die Login-Shell – der Benutzer homex hat keine, also bash vorgeben
Environment=SHELL=/bin/bash
Environment=LANG=C.UTF-8
ExecStart=/usr/bin/tmux -L homex -u new-session -d -s homex -x 200 -y 50 $BASE/start.sh
ExecStop=$BASE/stop.sh
Restart=on-failure
RestartSec=10
TimeoutStopSec=40

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/logrotate.d/homex <<ROT
$SERVER/logs/homex.log {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
}
ROT

install -m 755 "$SRC/installation/homex" /usr/local/bin/homex
systemctl daemon-reload
systemctl enable -q homex
if systemctl is-active -q homex; then
  systemctl restart homex
else
  systemctl start homex
fi

# ---------------------------------------------------------------- Fertig
sleep 8
schritt "Fertig"
if systemctl is-active -q homex; then
  gruen "HomeX läuft."
else
  rot "HomeX ist nicht gestartet – siehe: homex log"
fi
echo
/usr/local/bin/homex adresse || true
echo
echo "Wichtige Befehle:"
echo "  homex konsole    HomeX-Konsole öffnen (verlassen: Strg+B, dann D)"
echo "  homex log        Log live ansehen (verlassen: Strg+C)"
echo "  homex update     neuesten Stand von GitHub holen und neu starten"
echo "  homex hilfe      alle Befehle"
