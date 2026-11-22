# Kylie Mail on the web

Kylie Mail in a browser, at its own address on its own server. It aims to do everything the
Android app does, with the same desktop feel: the Delete key, right-click menus, keyboard shortcuts
and smart filing.

## Status

Everything the plan set out, plus search: passkey sign-in with recovery codes, reading, deleting,
archiving, moving and flagging with undo, smart filing, writing in plain text, and searching all
your folders. Not yet: notifications in the browser (your phone still gets them through the push
relay).

## Signing in

- **Passkeys only.** There's no password to steal or phish. A passkey saved in iCloud Keychain or
  Google Password Manager syncs to your other devices. Security keys work too if they support the
  PRF extension (also called hmac-secret); a passkey without it is turned away, because it couldn't
  lock your mail.
- **Recovery codes.** Setup gives you 10, each good for one sign-in. Print them or keep them in a
  password manager. You can make a fresh set at any time, which cancels the old ones.
- **Sessions** end after 2 hours without use, 12 hours at most, or when the server restarts.
- Wrong setup codes, sign-ins and recovery codes are rate limited.

## Looks

The app's own design: its palette (purple on lavender-tinted neutrals, in light and dark), the Inter
font it ships (copied from `app/src/main/res/font` when the web server is built), its tighter
corners and its icon. Like the app, it follows the system's light or dark setting unless you choose
one in Settings → Appearance; that choice is kept in the browser.

## Reading

- **Folders, messages and the message itself**, side by side on a big screen and one at a time on
  a phone. Opening a message marks it read, like the app.
- **The folder list is remembered**, since its unread counts take one request per folder on
  iCloud: it shows at once, even right after signing in (it's kept sealed with your account key,
  like everything else about your mail). Counts are freshened only for folders that just changed,
  the whole list every 10 minutes in the background, and the ↻ button asks for all of it.
- **New mail shows up the moment it arrives**, as in the app: while a page is open, the server keeps
  one extra iCloud connection in IMAP IDLE on your inbox, and a live stream (Server-Sent Events)
  tells the page whenever the inbox changes. The stream only ever says "the inbox changed", never
  anything about the mail; the page then fetches the list as usual. It updates even in a background
  tab, so the tab's title always shows how many are unread. The watcher reconnects by itself,
  renews IDLE every 20 minutes, and closes 5 minutes after the last page stops listening or when
  you sign out.
- **A regular check is the safety net**: once a minute while the tab is visible (every 5 minutes
  while the live stream is up), and as soon as you come back to the tab. It catches changes in
  other folders too. The ↻ button checks right away. New mail never moves your selection, the
  message you're reading or where the list is scrolled to.
- **None of this keeps you signed in**: the stream and the checks don't count as using the page,
  so a tab you've walked away from still signs out after 2 idle hours.
- **HTML email gets three locks.** The server strips anything active (scripts, forms, frames,
  plugins, event handlers, refreshes, outside style sheets). The message is then shown in a
  sandboxed frame where scripts can't run at all, under its own content security policy that
  blocks every outside request. Links open in a new tab without telling the site where you came
  from.
- **Images from the internet stay hidden** until you choose *Load images*, because they can tell
  the sender when you opened the message. *Always load from this sender* remembers your choice,
  sealed with your account key like everything else.
- **Attachments always download**, never open on the site, so an HTML or SVG attachment can't run
  inside Kylie Mail.
- **Mail comes live from iCloud** over the session's own connection, which closes when the
  session ends. The only things about your mail kept on the server's disk are sealed with your
  account key: the folder list, smart filing's memory and the senders you load images from.

## Acting on mail

Like the app on a big screen:

| Do this | Mouse | Keyboard |
|---|---|---|
| Select one | Click | <kbd>↑</kbd> <kbd>↓</kbd> or <kbd>J</kbd> <kbd>K</kbd> |
| Select several | <kbd>⌘</kbd>/<kbd>Ctrl</kbd>-click, <kbd>Shift</kbd>-click | <kbd>Shift</kbd>+<kbd>↑</kbd> <kbd>↓</kbd>, <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>A</kbd> |
| Delete | Right-click, toolbar | <kbd>Delete</kbd> or <kbd>Backspace</kbd> |
| Archive | Right-click, toolbar | <kbd>E</kbd> |
| Move to a folder | Right-click, toolbar | <kbd>M</kbd> or <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>M</kbd> |
| Mark read or unread | Right-click, toolbar | <kbd>U</kbd> |
| Flag | Right-click, toolbar | <kbd>S</kbd> |
| Undo a delete, archive or move | *Undo* on the notice | <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>Z</kbd> |
| Go to a folder | Folder list | <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>K</kbd> |
| New message | ✏️ | <kbd>N</kbd> |
| Reply, reply all, forward | Right-click, toolbar | <kbd>R</kbd>, <kbd>A</kbd>, <kbd>F</kbd> |
| Edit a draft | In Drafts: toolbar, right-click | <kbd>Enter</kbd> |
| Clear the selection | | <kbd>Esc</kbd> |
| Search | Search box | <kbd>/</kbd> or <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>F</kbd>, <kbd>Esc</kbd> to leave |

Deleting moves to Trash; deleting from Trash deletes for good. As in the app, deletes, archives and
moves wait 6 seconds before reaching iCloud, so Undo is instant. The wait happens on the server, so
closing the tab doesn't cancel them, and signing out sends any that are still waiting. Marking
read or flagged happens right away.

## Smart filing

The app's own filing model, suggesting where mail goes from who sent it, which address it went to,
the sender's company and words in the subject. No AI, and never the message itself.

- **Train it** from Settings → Smart filing → *Train smart filing…*, a window of its own as in the
  app: check the folders you file mail into (*All folders* checks them all), and it reads the
  sender, recipients and subject of up to 1,000 recent emails in each, showing its progress; you
  can close the window and it keeps going. What it learned stays between sign-ins, and each time
  you sign in it picks up mail filed into those folders since, including from your other devices.
- **It learns from you**: every Move and Archive is a lesson, and an undone one isn't. Deleting
  isn't filing.
- **Suggestions** appear as one-click *File to* buttons above a message when it's confident (hover
  for why), or *Choose a folder…* when it isn't, as *File to …* at the top of the right-click menu,
  and as a *Suggested* section in the Move picker. They come from the sender, recipients and
  subject already read with the message list, so they show up straight away rather than after the
  message has downloaded, and the bar keeps its size from the start so the message never shifts.
- **What it learns is sealed** with your account key in `filing.sealed`, like the password, and is
  only opened in memory while you're signed in.

## Search

The search box above the list searches every folder as you type, or just the open one with *All
folders* unchecked. Like the app, it understands `from:`, `to:`, `subject:` and `body:`, and
"quoted phrases"; other words match anywhere, every word must match, and part of a word counts
("madi" finds Madison). Results show which folder each is in, and you can open, reply to, delete,
move or flag them, even several from different folders at once, with Undo as usual.

iCloud does the searching (IMAP SEARCH, with the app's own query parser), so nothing about your
mail is indexed or stored on the server. It returns the newest 50 matches in each folder, as the
app's server search does, and says which folders had more or couldn't be searched.

## Writing

- **Plain text only, like the app.** Messages are built by the app's own `MessageComposer`: one
  plain-text part, files as standard attachments, never HTML, no tracking, no extra headers. Bcc
  recipients stay invisible to everyone, and reply headers coming from the browser can't add
  headers of their own.
- **Replies, reply all and forwards** come from the app's `ReplyBuilder`: quoted, with the right
  recipients and subject. Forwards carry the original's attachments. Your name and signature are
  set in Settings and sealed with your account key.
- **Send** with <kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>Enter</kbd>. It waits 6 seconds so *Undo*
  brings it back into the window, as in the app. Then it goes over iCloud's SMTP, a copy goes in
  Sent unless iCloud already filed one, and the original is marked answered. The wait is on the
  server: closing the tab doesn't stop it, and signing out sends it right away.
- **Drafts.** <kbd>Esc</kbd> closes the window and saves what you wrote to iCloud's Drafts folder
  (an empty message just closes). Open a draft there to keep writing; saving or sending it replaces
  the old one.
- **Attachments** by picking or dropping files, up to 14 MB in all, iCloud's limit with room for
  encoding. While you write, they wait in the container's temporary folder, which is memory
  (tmpfs), never the disk, and they're deleted once sent or discarded, or when you sign out.
- **Addresses are suggested** from people who wrote to you lately and people you wrote to, gathered
  once per session and kept in memory only.

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

To try the pages at http://localhost:8080 against a pretend iCloud, a local IMAP server holding a
few sample messages (sign up with `kylie@icloud.com` and the password `test`):

    ./gradlew -p web runLocal

It prints the setup code. Its data goes to `web/build/local-data`; delete that folder to start over.

The tests drive sign-up, sign-in and recovery end to end with a software passkey
(`SoftwarePasskey.kt`), including phishing attempts from another site and replayed sign-ins, and
read mail from a local IMAP server (`TestMail.kt`), including a newsletter that tries every trick
in the book.
