#!/usr/bin/env bash
# ======================================================================
#  ConnectLife-Bridge für die Hisense-Klimas einrichten (auf demselben
#  Server wie HomeX). Als root ausführen:   bash klima-bridge.sh
#
#  Voraussetzung im Proxmox-Container: Optionen → Features → "Nesting"
#  (und bei unprivilegiertem Container "keyctl") eingeschaltet – sonst
#  startet Docker nicht.
# ======================================================================
set -euo pipefail

BRIDGE=/opt/homex/bridge
KLIMA_CONFIG=/opt/homex/server/plugins/HisenseKlima/config.properties

gruen() { printf '\033[32m%s\033[0m\n' "$*"; }
rot()   { printf '\033[31m%s\033[0m\n' "$*" >&2; }
schritt() { printf '\n\033[1;35m▶ %s\033[0m\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { rot "Bitte als root ausführen."; exit 1; }

schritt "Docker installieren"
export DEBIAN_FRONTEND=noninteractive
if ! command -v docker >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq docker.io docker-compose >/dev/null
fi
systemctl enable -q --now docker 2>/dev/null || true
if ! docker info >/dev/null 2>&1; then
  rot "Docker startet nicht. Im Proxmox-Container unter Optionen → Features 'Nesting' (und 'keyctl') einschalten,"
  rot "den Container neu starten und dieses Skript nochmal ausführen."
  exit 1
fi
if docker compose version >/dev/null 2>&1; then DC="docker compose"; else DC="docker-compose"; fi
gruen "OK ($($DC version --short 2>/dev/null || echo compose))"

schritt "ConnectLife-Zugangsdaten"
mkdir -p "$BRIDGE"
if [ -f "$BRIDGE/.env" ] && [ -t 0 ]; then
  read -rp "Zugangsdaten sind schon gespeichert. Neu eingeben? [j/N] " neu
else
  neu=j
fi
if [ "${neu,,}" = j ]; then
  echo "Dieselben Daten wie in der ConnectLife-App. (Anmeldung per Google/Apple? Dann in der App erst ein Passwort setzen.)"
  read -rp "  E-Mail:   " MAIL
  read -rsp "  Passwort: " PASS; echo
  umask 077
  printf 'CONNECTLIFE_LOGIN=%s\nCONNECTLIFE_PASSWORD=%s\nLOG_LEVEL=info\n' "$MAIL" "$PASS" > "$BRIDGE/.env"
  chmod 600 "$BRIDGE/.env"
fi

cat > "$BRIDGE/docker-compose.yml" <<'YML'
# ConnectLife API Connector (https://github.com/bilan/connectlife-api-connector)
# Nur von diesem Server aus erreichbar (127.0.0.1) – HomeX spricht direkt mit ihm.
services:
  connectlife-bridge:
    build:
      context: https://github.com/bilan/connectlife-api-connector.git
      args:
        BUILD_FROM: alpine:3.20
    container_name: connectlife-bridge
    restart: unless-stopped
    env_file: .env
    ports:
      - "127.0.0.1:8000:8000"
    command: ["/bin/ash", "-c", "php artisan serve --port=8000 --host=0.0.0.0"]
YML

schritt "Bridge bauen und starten (dauert beim ersten Mal ein paar Minuten)"
( cd "$BRIDGE" && $DC up -d --build )

schritt "Warte auf die Klimas"
ok=""
for _ in $(seq 1 40); do
  if antwort=$(curl -fsS -m 10 http://127.0.0.1:8000/api/devices 2>/dev/null); then ok=1; break; fi
  sleep 3
done
if [ -z "$ok" ]; then
  rot "Die Bridge antwortet nicht. Die letzten Zeilen aus ihrem Log:"
  docker logs --tail 30 connectlife-bridge 2>&1 || true
  rot "Schick mir diese Ausgabe, dann schauen wir weiter."
  exit 1
fi
anzahl=$(grep -o '"puid"' <<<"$antwort" | wc -l)
gruen "Bridge läuft, $anzahl Gerät(e) bei ConnectLife gefunden."

schritt "HomeX verbinden"
if [ -f "$KLIMA_CONFIG" ]; then
  sed -i 's#^bridge.url=.*#bridge.url=http://127.0.0.1:8000#' "$KLIMA_CONFIG"
  chown homex:homex "$KLIMA_CONFIG"
  homex befehl reload >/dev/null 2>&1 || true
  sleep 5
  homex befehl klima liste || true
  gruen "Fertig – die Klimas erscheinen im Dashboard unter Klima."
else
  rot "$KLIMA_CONFIG fehlt – läuft HomeX mit dem Plugin HisenseKlima? (homex befehl plugins)"
fi
