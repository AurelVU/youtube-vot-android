#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
vot_sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
rm -rf build/host build/stubs build/extension-classes build/dex
mkdir -p build/host build/stubs build/extension-classes build/dex
json_jar="build/json-20240303.jar"
if [ ! -f "$json_jar" ]; then
  curl --fail --location --silent --show-error https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$json_jar"
fi
find core/src core/tests -name '*.java' | sort > build/core-sources.txt
javac --release 17 -Xlint:all -Werror -cp "$json_jar" -d build/host @build/core-sources.txt
for test in VotProtocolTest VotSyncTest VotPlaybackStateTest VotClientTest StatelessHttpTest VotControlsUiTest; do
  java -ea -cp "build/host:$json_jar" "io.github.aurelvu.vot.$test"
done
javac --release 17 -Xlint:all -Werror -d build/volume-tests android/test-stubs/android/media/*.java android/src/io/github/aurelvu/vot/VotOriginalAudio.java android/tests/io/github/aurelvu/vot/VotOriginalAudioTest.java
java -ea -cp build/volume-tests io.github.aurelvu.vot.VotOriginalAudioTest
find stubs -name '*.java' | sort > build/stub-sources.txt
javac --release 17 -classpath "$vot_sdk/platforms/android-36/android.jar" -d build/stubs @build/stub-sources.txt
find core/src android/src -name '*.java' | sort > build/extension-sources.txt
javac --release 17 -Xlint:all,-deprecation,-serial -classpath "$vot_sdk/platforms/android-36/android.jar:build/stubs" -d build/extension-classes @build/extension-sources.txt
jar --create --file build/extension-classes.jar -C build/extension-classes .
"$vot_sdk/build-tools/36.0.0/d8" --min-api 26 --lib "$vot_sdk/platforms/android-36/android.jar" --classpath build/stubs --output build/dex build/extension-classes.jar
cp build/dex/classes.dex build/vot.dex
bash scripts/build-patch.sh
