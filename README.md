**English** · [Deutsch](README.de.md)

# E-Mail for Android

A simple, modern email app for Android 12 and newer, written in Kotlin with Jetpack Compose and Material 3. It is the Android version of [E-Mail for Linux](https://github.com/deviablo/email).

Several accounts, IMAP and SMTP, nothing else: no calendar, no chat, no contact management.

## Features

- **Several accounts, strictly separate:** every account on its own, without a shared inbox. The guided setup recognises your provider, finds the server settings and helps with app passwords.
- **Conversations:** replies sit right below the message, exactly one message is open at a time. Swipe to mark as read or delete, with “Undo”.
- **Writing:** formatting, pictures in the text, attachments, Cc and Bcc, one signature per account. Drafts are saved automatically, and mail written while offline goes out as soon as there is a connection.
- **Search** across the whole account, with filters, also on the server.
- **Notifications through the system,** per account if you like, with “Mark as read” and “Delete”. The app checks every 15 minutes; with “Notify immediately” it keeps a connection open and reports new mail within seconds.
- **Phone and tablet:** on wide screens the list and the conversation sit side by side. Printing through the system dialog.
- **Private:** external images load only after you allow them, passwords are encrypted with the Android Keystore and never leave the device except to sign in, no cloud backup.
- **Languages:** English and German.

Every provider with IMAP and SMTP works, for example Gmail, GMX, WEB.DE, iCloud, Yahoo, Posteo, mailbox.org and most company and hosting mailboxes. Microsoft accounts (Outlook, Hotmail, Office 365) are not supported, because Microsoft no longer allows mail apps to sign in with a password.

## Install

1. Open [Releases](https://github.com/deviablo/email-android/releases/latest) on your phone and download `e-mail-<version>.apk`.
2. Open the downloaded file. Android asks once whether your browser may install apps: allow it.
3. Tap “Install”. “E-Mail” then shows up among your apps.

Updates are installed the same way, over the existing app; your accounts stay.

Tip: set up a test account first, not your most important mailbox.

To uninstall, long-press the app icon and choose “Uninstall”.

## Build it yourself

You need a JDK 21 (Android Studio's own works) and the Android SDK.

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

The APK is then in `app/build/outputs/apk/debug/`. Debug builds trust the test certificate in `app/src/debug/res/raw/test_ca.pem`, so they can talk to the local test server:

```bash
build-aux/test-mail-server.sh
```

It starts [GreenMail](https://greenmail-mail-test.github.io/greenmail/) on your computer with a few sample mails (IMAP 3993, SMTP 3465, user `max.berger@example.org`, password `secret`) and forwards the ports into a running emulator. In the app, enter the servers yourself: `127.0.0.1`, SSL/TLS.

Releases are built by GitHub Actions (`.github/workflows/build.yml`) when a tag `v*` is pushed; the signing key comes from the repository secrets.

## Contributing

Please report bugs and ideas as an [issue](https://github.com/deviablo/email-android/issues).

## License

E-Mail is an open community project by the E-Mail developers.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but **without any warranty**; without even the implied warranty of merchantability or fitness for a particular purpose. See the GNU General Public License for more details.

The full license text is in the file [`LICENSE`](LICENSE).

`SPDX-License-Identifier: GPL-3.0-or-later`
