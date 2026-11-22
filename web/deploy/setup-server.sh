#!/usr/bin/env bash
# One-time setup for a fresh Debian 13 server that runs only Kylie Mail on the web.
# This server reads mail, so it shares nothing with the push relay's server.
# Run as root:  bash setup-server.sh mail.example.org
# Safe to run again.
set -euo pipefail

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  echo "Usage: bash setup-server.sh mail.example.org" >&2
  exit 1
fi
if [[ "$(id -u)" != 0 ]]; then
  echo "Run this as root." >&2
  exit 1
fi
APP=/opt/kylie-mail-web

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }

# Docker's package list and the security settings below are for Debian 13 only.
. /etc/os-release
if [[ "${ID:-}" != debian || "${VERSION_ID:-}" != 13 ]]; then
  echo "This script is for Debian 13, but this server runs ${PRETTY_NAME:-something else}." >&2
  echo "Reinstall it with Debian 13 (in Vultr: Settings, Change OS), then run this again. Nothing has been changed." >&2
  exit 1
fi

# Turning off password logins with no key in place would lock you out, so check first.
say "Checking for an SSH key"
if ! grep -qE '^(ssh-(ed25519|rsa)|ecdsa-sha2-|sk-)' /root/.ssh/authorized_keys 2>/dev/null; then
  echo "No SSH key in /root/.ssh/authorized_keys. Add yours first (Vultr can do this when reinstalling)," >&2
  echo "then log in with it and run this again. Nothing has been changed." >&2
  exit 1
fi

say "Updating the system"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get -y -q upgrade
apt-get -y -q install ca-certificates curl ufw unattended-upgrades fail2ban

say "Turning on automatic security updates"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF
# Kernel and library updates only take effect after a restart, so restart at a quiet hour when needed.
cat > /etc/apt/apt.conf.d/52kylie-mail-reboot <<'CONF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
CONF

say "SSH: keys only"
cat > /etc/ssh/sshd_config.d/10-kylie-mail.conf <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
X11Forwarding no
AllowAgentForwarding no
MaxAuthTries 3
CONF
sshd -t
systemctl reload ssh

say "Blocking repeated SSH login attempts"
cat > /etc/fail2ban/jail.d/sshd.local <<'CONF'
[sshd]
enabled = true
backend = systemd
maxretry = 5
bantime = 1h
CONF
systemctl enable --now fail2ban >/dev/null
systemctl restart fail2ban

say "Firewall: SSH and HTTPS only"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw limit OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 443/udp >/dev/null
ufw --force enable >/dev/null

if ! swapon --show | grep -q /swapfile; then
  say "Adding 1 GB of swap, for breathing room"
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

if ! command -v docker >/dev/null; then
  say "Installing Docker"
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get -y -q install docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi

say "Setting up $APP"
mkdir -p "$APP"
chmod 700 "$APP"
echo "DOMAIN=$DOMAIN" > "$APP/.env"

cat > "$APP/compose.yaml" <<'CONF'
# Kylie Mail on the web and the HTTPS server in front of it.
# Lives in /opt/kylie-mail-web on the server; see web/README.md.
services:
  web:
    image: ghcr.io/kayloh20/kylie-mail-web:latest
    restart: unless-stopped
    environment:
      PUBLIC_ORIGIN: https://${DOMAIN}
    volumes:
      - web-data:/data
    # Not reachable from the internet directly: only Caddy talks to it.
    expose:
      - "8080"
    read_only: true
    tmpfs:
      - /tmp
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    mem_limit: 1g
    pids_limit: 256
    logging: &logging
      driver: json-file
      options:
        max-size: "5m"
        max-file: "3"

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      DOMAIN: ${DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    depends_on:
      - web
    mem_limit: 200m
    logging: *logging

volumes:
  web-data:
  caddy-data:
  caddy-config:
CONF
cat > "$APP/Caddyfile" <<'CONF'
# No access logs: they would record which messages were opened.
# Kylie Mail sets its own security headers, including HSTS. No compression: compressing pages that
# hold secrets alongside text others can send you (mail) can leak them (BREACH).
# Caddy gets and renews the HTTPS certificate for $DOMAIN by itself.
{$DOMAIN} {
	request_body {
		# Room for attachments on outgoing mail; nothing bigger gets in.
		max_size 40MB
	}
	reverse_proxy web:8080
	header -Server
}
CONF

say "Checking DNS"
RESOLVED="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1 {print $1}' || true)"
if [[ -z "$RESOLVED" ]]; then
  echo "Warning: $DOMAIN doesn't resolve yet. HTTPS won't work until its A record points here."
elif ! hostname -I | tr ' ' '\n' | grep -qx "$RESOLVED"; then
  echo "Warning: $DOMAIN points to $RESOLVED, which isn't this server. Check the A record."
else
  echo "$DOMAIN points here. 👍"
fi

say "Done"
cat <<NEXT

Next:
  1. Log in to GitHub's registry once: docker login ghcr.io -u kayloh20
     (password: a GitHub token with only "read:packages")
  2. Start it:                         cd $APP && docker compose up -d
  3. Check it:                         curl https://$DOMAIN/healthz   (should say ok)

NEXT
