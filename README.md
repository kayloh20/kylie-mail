# Kylie Mail

A desktop-first Android email client for iCloud Mail. It is built for Googlebook and other
large screens first and also works on phones.

## Status

Early development. What works today:

- Sign in to iCloud Mail with an app-specific password, stored encrypted with the Android Keystore
- Folder list with unread counts, and iCloud's special folders (Sent, Trash, Junk and so on) recognised
- Inbox sync that saves to the device first, with flag changes and server-side deletions picked up
- Background sync every 15 minutes
- Adaptive layout: three panes on wide windows, two on medium, one on phones
- Resizable message list, arrow keys to move through messages, Esc to close one, selectable message text

Planned next: notifications with near-instant delivery (IMAP IDLE), an offline action queue for
delete, move, mark read and flag, right-click menus, multi-select, a move-to-folder shortcut, and
suggested filing.

## On the web

The web version lives in [`web/`](web/README.md) and runs on its own server. It does what the
app does on a big screen: passkey sign-in, reading, deleting, moving, flagging, smart filing,
writing in plain text and search.

## Signing in

iCloud requires an app-specific password for mail apps other than Apple Mail:

1. Go to [account.apple.com](https://account.apple.com) and sign in.
2. Open **Sign-In and Security**, then **App-Specific Passwords**, and create one.
3. In Kylie Mail, enter your @icloud.com address and that password.

## Installing a build

Every push to `main` that changes the app builds a signed release APK on GitHub Actions. Open the
latest run of the **Android** workflow under **Actions**, download the `kylie-mail-*` artifact,
unzip it and install the APK. Each build installs over the previous one.

The APK is built alongside the tests, so it's ready before they finish. If the run goes red, don't
install that one.

CI is split by part of the repository, and each workflow runs only when its part changes:
**Android** (`app/`), **Relay** (`relay/`) and **Web** (`web/`, plus the app's mail code, which the
web server compiles too).

Release builds are signed with a private key that lives only in the repository's GitHub secrets:

- `RELEASE_KEYSTORE_BASE64`: the PKCS12 keystore, base64 encoded
- `RELEASE_KEYSTORE_PASSWORD`: its password (also used for the key)

Debug builds install as a separate app, "Kylie Mail Debug" (`com.kayloh20.kyliemail.debug`).

## Building locally

Requires JDK 17 or newer and the Android SDK (platform 36).

```bash
./gradlew assembleDebug testDebugUnitTest
```

To build a signed release locally, set `RELEASE_KEYSTORE_FILE` to the keystore path and
`RELEASE_KEYSTORE_PASSWORD` to its password, then run `./gradlew assembleRelease`.
