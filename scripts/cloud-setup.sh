#!/usr/bin/env bash
# Sets up a fresh cloud container to build and test GradatiON: Android SDK, Gradle 9.5.0 and the
# Maven Central mirror. Idempotent. Paste it into the environment's Setup script, or run it by hand.
# Needs dl.google.com, services.gradle.org and maven-central.storage-download.googleapis.com.
set -euo pipefail

SDK=/opt/android-sdk
GRADLE_VERSION=9.5.0

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK/cmdline-tools"
  curl -sS -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/cmdline-tools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm /tmp/cmdline-tools.zip
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"

# The wrapper's own download fails behind the proxy, so fetch Gradle directly.
if [ ! -x "/opt/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  curl -sSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q /tmp/gradle.zip -d /opt
  rm /tmp/gradle.zip
fi

# Maven Central returns 429s: put the Google mirror first.
mkdir -p ~/.gradle/init.d
cat > ~/.gradle/init.d/mirror.gradle <<'EOF'
def mirror = { repos -> repos.maven { name 'gcsCentral'; url 'https://maven-central.storage-download.googleapis.com/maven2/' }; def r = repos.remove(repos.size()-1); repos.add(0, r) }
settingsEvaluated { s -> mirror(s.pluginManagement.repositories); mirror(s.dependencyResolutionManagement.repositories) }
EOF

grep -q ANDROID_HOME ~/.bashrc 2>/dev/null || echo "export ANDROID_HOME=$SDK" >> ~/.bashrc
echo "Done. Build with: ANDROID_HOME=$SDK /opt/gradle-$GRADLE_VERSION/bin/gradle assembleDev"
