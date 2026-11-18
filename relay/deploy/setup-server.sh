#!/usr/bin/env bash
# One-time setup for a fresh Debian 13 server that runs only Kylie Mail's push relay.
# Run as root:  bash setup-server.sh mail-push.example.org
# Safe to run again.
set -euo pipefail

DOMAIN="${1:-}"
if [[ -z "$DOMAIN" ]]; then
  echo "Usage: bash setup-server.sh mail-push.example.org" >&2
  exit 1
fi
if [[ "$(id -u)" != 0 ]]; then
  echo "Run this as root." >&2
  exit 1
fi
APP=/opt/mail-push

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }

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
apt-get -y -q install ca-certificates curl ufw unattended-upgrades

say "Turning on automatic security updates"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
CONF

say "SSH: keys only"
cat > /etc/ssh/sshd_config.d/10-mail-push.conf <<'CONF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
CONF
sshd -t
systemctl reload ssh

say "Firewall: SSH and HTTPS only"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 443/udp >/dev/null
ufw --force enable >/dev/null

if ! swapon --show | grep -q /swapfile; then
  say "Adding 1 GB of swap, for breathing room on a small server"
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
mkdir -p "$APP/secrets"
chmod 700 "$APP/secrets"
echo "DOMAIN=$DOMAIN" > "$APP/.env"

cat > "$APP/compose.yaml" <<'CONF'
# Kylie Mail's push relay and the HTTPS server in front of it.
# Lives in /opt/mail-push on the server; see relay/README.md.
services:
  relay:
    image: ghcr.io/kayloh20/kylie-mail-relay:latest
    restart: unless-stopped
    environment:
      SETUP_CODE_FILE: /run/secrets/setup-code
      FIREBASE_KEY_FILE: /run/secrets/firebase.json
    volumes:
      - relay-data:/data
      - ./secrets/setup-code:/run/secrets/setup-code:ro
      - ./secrets/firebase.json:/run/secrets/firebase.json:ro
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
    mem_limit: 400m
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
      - relay
    mem_limit: 200m
    logging: *logging

volumes:
  relay-data:
  caddy-data:
  caddy-config:
CONF
cat > "$APP/Caddyfile" <<'CONF'
# Caddy gets and renews the HTTPS certificate for $DOMAIN by itself.
{$DOMAIN} {
	# Only the relay's own paths; everything else is a 404.
	@relay path /v1/* /healthz
	handle @relay {
		reverse_proxy relay:8080
	}
	handle {
		respond 404
	}
	header -Server
}
CONF

if [[ ! -s "$APP/secrets/setup-code" ]]; then
  # The code a device needs to sign up with this relay.
  head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' > "$APP/secrets/setup-code"
fi
# The relay runs as user 10001 and only needs to read these.
chown 10001 "$APP/secrets/setup-code"
chmod 400 "$APP/secrets/setup-code"
if [[ -f "$APP/secrets/firebase.json" ]]; then
  chown 10001 "$APP/secrets/firebase.json"
  chmod 400 "$APP/secrets/firebase.json"
fi

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
  1. Copy your Firebase key here:      $APP/secrets/firebase.json
     then run:                         bash $0 $DOMAIN    (again, to fix its permissions)
  2. Log in to GitHub's registry once: docker login ghcr.io -u kayloh20
     (password: a GitHub token with only "read:packages")
  3. Start it:                         cd $APP && docker compose up -d
  4. Check it:                         curl https://$DOMAIN/healthz   (should say ok)

Your setup code (Kylie Mail asks for it once, when you turn the relay on):

  $(cat "$APP/secrets/setup-code")

NEXT
