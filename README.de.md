[English](README.md) · **Deutsch**

# E-Mail für Android

Eine einfache, moderne Mail-App für Android 12 und neuer, geschrieben in Kotlin mit Jetpack Compose und Material 3. Sie ist die Android-Fassung von [E-Mail für Linux](https://github.com/deviablo/email).

Mehrere Konten, IMAP und SMTP, sonst nichts: kein Kalender, kein Chat, keine Kontaktverwaltung.

## Funktionen

- **Mehrere Konten, streng getrennt:** jedes Konto für sich, ohne gemeinsamen Posteingang. Die geführte Einrichtung erkennt deinen Anbieter, findet die Servereinstellungen und hilft bei App-Passwörtern.
- **Unterhaltungen:** Antworten stehen direkt unter der Nachricht, es ist immer genau eine Nachricht aufgeklappt. Wischen markiert als gelesen oder löscht, mit „Rückgängig“.
- **Schreiben:** Formatierung, Bilder im Text, Anhänge, Cc und Bcc, eine Signatur pro Konto. Entwürfe werden automatisch gespeichert, und was du ohne Verbindung schreibst, geht raus, sobald wieder Netz da ist.
- **Suche** im ganzen Konto, mit Filtern, auch auf dem Server.
- **Benachrichtigungen über das System,** auf Wunsch pro Konto, mit „Als gelesen markieren“ und „Löschen“. Die App schaut alle 15 Minuten nach; mit „Sofort benachrichtigen“ hält sie eine Verbindung offen und meldet neue Mails nach wenigen Sekunden.
- **Telefon und Tablet:** auf breiten Bildschirmen stehen Liste und Unterhaltung nebeneinander. Drucken über den Dialog des Systems.
- **Privat:** externe Bilder laden erst, wenn du es erlaubst, Passwörter liegen verschlüsselt im Android-Schlüsselspeicher und verlassen das Gerät nur zur Anmeldung, keine Cloud-Sicherung.
- **Sprachen:** Deutsch und Englisch.

Es funktioniert jeder Anbieter mit IMAP und SMTP, zum Beispiel Gmail, GMX, WEB.DE, iCloud, Yahoo, Posteo, mailbox.org und die meisten Firmen- und Hosting-Postfächer. Microsoft-Konten (Outlook, Hotmail, Office 365) werden nicht unterstützt, weil Microsoft Mailprogrammen die Anmeldung mit Passwort nicht mehr erlaubt.

## Installieren

1. Öffne auf dem Telefon [Releases](https://github.com/deviablo/email-android/releases/latest) und lade `e-mail-<version>.apk` herunter.
2. Öffne die heruntergeladene Datei. Android fragt einmal, ob dein Browser Apps installieren darf: erlauben.
3. Auf „Installieren“ tippen. „E-Mail“ erscheint dann bei deinen Apps.

Updates installierst du genauso, über die vorhandene App; deine Konten bleiben erhalten.

Tipp: Richte zuerst ein Testkonto ein, nicht gleich dein wichtigstes Postfach.

Zum Deinstallieren lange auf das App-Symbol tippen und „Deinstallieren“ wählen.

## Selbst bauen

Du brauchst ein JDK 21 (das von Android Studio genügt) und das Android-SDK.

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

Die APK liegt dann in `app/build/outputs/apk/debug/`. Debug-Builds vertrauen dem Testzertifikat in `app/src/debug/res/raw/test_ca.pem` und können so mit dem lokalen Testserver sprechen:

```bash
build-aux/test-mail-server.sh
```

Das startet [GreenMail](https://greenmail-mail-test.github.io/greenmail/) auf deinem Rechner mit ein paar Beispiel-Mails (IMAP 3993, SMTP 3465, Benutzer `max.berger@example.org`, Passwort `secret`) und leitet die Ports in einen laufenden Emulator weiter. In der App die Server selbst eintragen: `127.0.0.1`, SSL/TLS.

Veröffentlichungen baut GitHub Actions (`.github/workflows/build.yml`), sobald ein Tag `v*` hochgeladen wird; der Signaturschlüssel kommt aus den Secrets des Repositorys.

## Mitmachen

Fehler und Ideen bitte als [Issue](https://github.com/deviablo/email-android/issues) melden.

## Lizenz

E-Mail ist ein offenes Gemeinschaftsprojekt der Entwicklerinnen und Entwickler von E-Mail.

Dieses Programm ist freie Software: Du kannst es unter den Bedingungen der GNU General Public License, wie von der Free Software Foundation veröffentlicht, weitergeben und/oder verändern, entweder gemäß Version 3 der Lizenz oder (nach deiner Wahl) jeder späteren Version.

Dieses Programm wird in der Hoffnung verbreitet, dass es nützlich ist, aber **ohne jede Gewährleistung**; sogar ohne die stillschweigende Gewährleistung der Marktgängigkeit oder der Eignung für einen bestimmten Zweck. Einzelheiten stehen in der GNU General Public License.

Der vollständige Lizenztext steht in der Datei [`LICENSE`](LICENSE).

`SPDX-License-Identifier: GPL-3.0-or-later`
