#!/usr/bin/env bash
# One-time VPS setup for GadScape, targeting Ubuntu 24.04.
#
# Run this AFTER uploading the deploy bundle (server.jar, worlds.yml, data/, cache/,
# app/, plus this deploy/ folder) to a staging directory on the VPS - see README.md
# for the exact upload layout. Run it from inside that staging directory:
#
#   cd ~/gadscape-upload
#   sudo bash deploy/setup.sh
#
# It installs OpenJDK 21, creates a dedicated non-root 'gadscape' user, moves the
# uploaded files into /opt/gadscape, opens the firewall, and installs+enables the
# systemd service. It does NOT start the service - see README.md for the final step.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run this as root (e.g. with sudo)." >&2
  exit 1
fi

STAGING_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="/opt/gadscape"
SERVICE_USER="gadscape"

echo "==> Installing OpenJDK 21..."
apt-get update -y
apt-get install -y openjdk-21-jre-headless ufw rsync

echo "==> Creating '$SERVICE_USER' system user..."
if ! id -u "$SERVICE_USER" &>/dev/null; then
  useradd --system --create-home --home-dir "$INSTALL_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

echo "==> Installing application files into $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
for item in server.jar worlds.yml data cache cache241 keys app; do
  if [[ -e "$STAGING_DIR/$item" ]]; then
    rsync -a --delete "$STAGING_DIR/$item" "$INSTALL_DIR/"
  else
    echo "WARNING: $STAGING_DIR/$item not found - skipping. The service will not start without it." >&2
  fi
done
cp "$STAGING_DIR/deploy/start.sh" "$INSTALL_DIR/start.sh"
chmod +x "$INSTALL_DIR/start.sh"
# Rev 241 settings (cache dir, RSA key, login token, ports); kept if it already exists.
if [[ ! -e "$INSTALL_DIR/start.env" ]]; then
  cp "$STAGING_DIR/deploy/start.env.example" "$INSTALL_DIR/start.env"
fi
mkdir -p "$INSTALL_DIR/logs"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo "==> Configuring firewall (ufw)..."
# 43594 is the primary client port. 443 is a real fallback the decompiled client
# retries automatically when 43594 is blocked (Client.java's worldPort/js5Port
# logic) - players on restrictive networks depend on it, so it's public too.
# 40001/50001 only activate for a client gameBuild != 0, which nothing here ever
# sets - the server binds those two loopback-only, so there is nothing to open.
ufw allow 22/tcp comment 'SSH'
ufw allow 43594/tcp comment 'GadScape game port'
ufw allow 443/tcp comment 'GadScape fallback port (client retries here if 43594 is blocked)'
ufw --force enable

echo "==> Installing systemd service..."
cp "$STAGING_DIR/deploy/gadscape.service" /etc/systemd/system/gadscape.service
systemctl daemon-reload
systemctl enable gadscape

echo "==> Done."
echo "Review $INSTALL_DIR/worlds.yml (real secrets, not the .example template) before starting."
echo "Then run: sudo systemctl start gadscape"
echo "Check status with: sudo systemctl status gadscape"
echo "Tail logs with: sudo journalctl -u gadscape -f   (or tail -f $INSTALL_DIR/logs/server.log)"
