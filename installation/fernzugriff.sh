#!/usr/bin/env bash
# Fernzugriff auf HomeX über Tailscale – ohne Portfreigabe (klappt auch hinter Starlink)
# und mit echtem HTTPS, damit das Handy HomeX als richtige App installieren kann.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Bitte als root ausführen." >&2; exit 1; }

PORT=$(grep -s '^port=' /opt/homex/server/plugins/WebAPI/config.properties | cut -d= -f2 | tr -d ' ')
PORT=${PORT:-8080}

# 1. Der Container braucht das TUN-Gerät (wird auf dem Proxmox-Host freigegeben)
if [ ! -c /dev/net/tun ]; then
  cat <<TXT

Im Container fehlt noch das Netzwerkgerät /dev/net/tun. Das gibst du einmal auf dem PROXMOX-HOST frei
(in Proxmox links auf den Server-Namen klicken → Shell, NICHT die Konsole des Containers).
Ersetze 101 durch die Nummer deines Containers, falls sie anders ist:

  cat >> /etc/pve/lxc/101.conf <<'X'
lxc.cgroup2.devices.allow: c 10:200 rwm
lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
X
  pct reboot 101

Danach hier im Container nochmal: homex fernzugriff
TXT
  exit 1
fi

# 2. Tailscale installieren
if ! command -v tailscale >/dev/null; then
  echo "==> Tailscale installieren"
  apt-get update -q && apt-get install -y -q curl ca-certificates >/dev/null
  curl -fsSL https://tailscale.com/install.sh | sh
fi
systemctl enable --now tailscaled >/dev/null

# 3. Anmelden (zeigt einen Link – im Browser öffnen und mit deinem Tailscale-Konto bestätigen)
if ! tailscale status >/dev/null 2>&1; then
  echo "==> Anmelden: öffne gleich den angezeigten Link und melde dich an"
  tailscale up --hostname homex
fi

# 4. HomeX per HTTPS im eigenen Tailscale-Netz bereitstellen
echo "==> HTTPS einrichten (beim ersten Mal kommt evtl. ein Link, um HTTPS im Tailscale-Konto einzuschalten)"
tailscale serve --bg "$PORT"

NAME=$(tailscale status --json | grep -m1 '"DNSName"' | cut -d'"' -f4 | sed 's/\.$//')
TOKEN=$(grep -s '^token=' /opt/homex/server/plugins/WebAPI/config.properties | cut -d= -f2 | tr -d ' ')
cat <<TXT

Fertig! HomeX von überall (Handy mit Tailscale-App, im selben Konto angemeldet):

  https://$NAME/

Direkt-Link (meldet sofort an):
  https://$NAME/?token=$TOKEN

TXT
