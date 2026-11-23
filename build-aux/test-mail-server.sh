#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Starts a local GreenMail test server (IMAPS 3993, SMTP 3025) with the project's test
# certificate and fills it with a few sample mails. Debug builds of the app trust the test CA,
# so the emulator can connect with full certificate checks.
#
# Usage:   build-aux/test-mail-server.sh          (runs in the foreground, Ctrl+C stops it)
# In the app (emulator): server 127.0.0.1, port 3993, SSL/TLS,
#                        address max.berger@example.org, password: secret
# The port is forwarded into the running emulator with "adb reverse": Android 17 blocks apps from
# the local network (10.0.2.2 counts as such) unless they hold the local-network permission.
#
# Needs Java 17+ (Android Studio's own JDK is used if found) and python3.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
cache="${XDG_CACHE_HOME:-$HOME/.cache}/e-mail-android"
version=2.1.15
sha1=aa88f486876b44512f412df8242a40c40c895f56
jar="$cache/greenmail-standalone-$version.jar"

java=java
[ -x /snap/android-studio/current/jbr/bin/java ] && java=/snap/android-studio/current/jbr/bin/java

mkdir -p "$cache"
if [ ! -f "$jar" ]; then
  curl -fsSL -o "$jar.part" "https://repo1.maven.org/maven2/com/icegreen/greenmail-standalone/$version/greenmail-standalone-$version.jar"
  echo "$sha1  $jar.part" | sha1sum -c --quiet
  mv "$jar.part" "$jar"
fi

# 127.0.0.1 only: nobody else on the network can reach it.
"$java" \
  -Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imaps -Dgreenmail.hostname=127.0.0.1 \
  -Dgreenmail.users=max.berger:secret@example.org,lena.koch:secret@example.org -Dgreenmail.users.login=email \
  -Dgreenmail.tls.keystore.file="$here/test-server/test-server.p12" \
  -Dgreenmail.tls.keystore.password=changeit -Dgreenmail.tls.key.password=changeit \
  -jar "$jar" &
server=$!
trap 'kill $server 2>/dev/null' INT TERM EXIT

python3 "$here/test-server/seed.py"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$adb" reverse tcp:3993 tcp:3993 >/dev/null 2>&1 || echo "Hinweis: kein Emulator gefunden – nach dem Start: adb reverse tcp:3993 tcp:3993"
echo "GreenMail läuft. In der App: Server 127.0.0.1, Port 3993, SSL/TLS, max.berger@example.org / secret"
wait $server
