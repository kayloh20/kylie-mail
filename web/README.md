# Kylie Mail on the web

Kylie Mail in a browser, at its own address on its own server. It aims to do everything the
Android app does, with the same desktop feel: the Delete key, right-click menus, keyboard shortcuts
and smart filing.

## Status

Signing in works: first-time setup, passkeys, recovery codes, and managing both. It doesn't show
any mail yet. Coming next, in this order:

1. **Reading.** Folders, the message list, and HTML email cleaned up on the server and shown in a
   sandbox with no scripts. Remote images stay blocked until you choose to load them.
2. **Acting on mail.** Delete, move, multi-select, right-click and the app's keyboard shortcuts.
3. **Smart filing**, using the app's own filing code.
4. **Writing.** Replies, forwards and new mail, in plain text only, like the app.

## Signing in

- **Passkeys only.** There's no password to steal or phish. A passkey saved in iCloud Keychain or
  Google Password Manager syncs to your other devices. Security keys work too if they support the
  PRF extension (also called hmac-secret); a passkey without it is turned away, because it couldn't
  lock your mail.
- **Recovery codes.** Setup gives you 10, each good for one sign-in. Print them or keep them in a
  password manager. You can make a fresh set at any time, which cancels the old ones.
- **Sessions** end after 2 hours without use, 12 hours at most, or when the server restarts.
- Wrong setup codes, sign-ins and recovery codes are rate limited.

## How your mail is protected

This server has to read your mail to show it, so it is a bigger target than the push relay. It
lives on a separate server that shares nothing with the relay.

- **Your app-specific password is stored sealed.** The key that opens it comes from your passkey
  (the WebAuthn PRF extension), so the server can't use the password, or reach your mail, unless you
  are signed in. A stolen disk or backup holds nothing readable.
- **Each passkey, and each recovery code, holds its own sealed copy** of that key. Losing one device
  doesn't lock you out.
- **No access logs, nothing cached.** Pages are sent with `Cache-Control: no-store`, and Caddy keeps
  no record of which messages you open.
- **Strict browser rules on every page.** The site runs only its own scripts, sends data only to
  itself, and can't be framed by other sites. Requests that change anything must come from its own
  pages.
- You can cut it off at any time by revoking the app-specific password at account.apple.com.

Because the server can't open your mail while you're signed out, it doesn't sync in the background.
It catches up when you sign in. Your phone keeps getting notifications through the push relay.

## Code shared with the app

The web server compiles the Android app's mail code that doesn't depend on Android directly from
`app/src/main/java` (see `sourceSets` in `build.gradle.kts`): the IMAP connection, message parsing,
smart filing, replies, sending and search. Changes there reach both. Only list files there that
import nothing from Android or the app's database.

## Setting up the server

You need a server that does nothing else (Debian 13; 2 GB of memory is comfortable) and a domain
name pointed at it. Every command runs on your own machine or on the server, by you.

1. **DNS.** Add an A record (and AAAA, if the server has IPv6) for your mail address, such as
   `mail.example.org`, pointing at the server. On Cloudflare, use *DNS only*.
2. **Copy the setup script to the server** from your computer:
   `scp web/deploy/setup-server.sh root@mail.example.org:`
3. **Run it** on the server as root: `bash setup-server.sh mail.example.org`. It turns on automatic
   security updates, makes SSH keys-only with repeated failed logins blocked, allows only SSH and
   HTTPS through the firewall, installs Docker, and writes everything to `/opt/kylie-mail-web`.
4. **Registry login**, once: `docker login ghcr.io -u kayloh20`, with a GitHub token that has only
   `read:packages` as the password.
5. **Start**: `cd /opt/kylie-mail-web && docker compose up -d`. Check `https://<your domain>/healthz`
   says `ok`.
6. **Set up your account.** Get the one-time setup code with
   `docker compose logs web | grep "setup code"`, open your mail address in a browser, and follow the
   steps. Once you're set up, the code stops working.

To start over (this forgets your passkeys and the sealed password; your mail stays in iCloud):
`cd /opt/kylie-mail-web && docker compose down && docker volume rm kylie-mail-web_web-data && docker compose up -d`.

To update: `cd /opt/kylie-mail-web && docker compose pull && docker compose up -d`.

## Developing

    ./gradlew -p web test

To try the pages at http://localhost:8080 with a pretend iCloud (any address, password `test`):

    ./gradlew -p web runLocal

It prints the setup code. Its data goes to `web/build/local-data`; delete that folder to start over.

The tests drive sign-up, sign-in and recovery end to end with a software passkey
(`SoftwarePasskey.kt`), including phishing attempts from another site and replayed sign-ins.
