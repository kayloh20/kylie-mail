# Kylie Mail push relay

Watches an iCloud inbox around the clock and wakes the user's devices through Firebase Cloud
Messaging the moment mail arrives, so the phone doesn't have to hold its own connection open.

## What it knows, and what it doesn't

- **Your app-specific password**, sealed with a key only your devices hold. The relay keeps a hash
  of that key to recognise your devices, never the key itself. After a restart it can't read the
  password until one of your devices checks in, which they do on their own.
- **Your devices' push tokens**, to wake them.
- **Nothing about your mail.** It never reads a message. It watches only the inbox's message count,
  and the push it sends says "new mail" and nothing else. Your device downloads the mail itself.
- Only devices with the **setup code** can sign up, so strangers can't use your relay.
- If iCloud rejects the password, it stops. Removing the last device deletes the account and password.
- You can cut it off any time by revoking the app-specific password at account.apple.com.

## Setting up the server

You need a server that does nothing else (Debian 13, 1 GB of memory is plenty), a domain name
pointed at it, and a Firebase project.

1. **Firebase.** At console.firebase.google.com, create a project. In *Project settings → Service
   accounts*, click *Generate new private key*. The downloaded file is the relay's Firebase key:
   keep it secret.
2. **DNS.** Add an A record (and AAAA, if the server has IPv6) for your relay's name pointing at
   the server. On Cloudflare, use *DNS only*.
3. **Set up the server.** Copy `deploy/setup-server.sh` to the server and run it as root:
   `bash setup-server.sh mail-push.example.org`. It turns on automatic security updates, makes SSH
   keys-only, allows only SSH and HTTPS through the firewall, installs Docker, and writes everything
   to `/opt/mail-push`. It prints the setup code at the end.
4. **Firebase key.** Copy it to `/opt/mail-push/secrets/firebase.json` on the server, then run the
   setup script once more to fix its permissions.
5. **Registry login**, once: `docker login ghcr.io -u kayloh20`, with a GitHub token that has only
   `read:packages` as the password.
6. **Start**: `cd /opt/mail-push && docker compose up -d`. Check `https://<your domain>/healthz`
   says `ok`.

To update: `cd /opt/mail-push && docker compose pull && docker compose up -d`.

## Developing

    ./gradlew -p relay test

The tests run the relay's HTTP API against a real IMAP server (GreenMail) with Firebase stubbed out.
