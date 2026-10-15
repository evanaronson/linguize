#!/usr/bin/env bash
# Real platform boundary: external UID owns clipboard, Settings is foreground, service overlays it.
set -euo pipefail
probe_dir=$(mktemp -d)
trap 'rm -rf "$probe_dir"' EXIT
platform="$ANDROID_HOME/platforms/android-35/android.jar"
build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
mkdir -p "$probe_dir/classes" "$probe_dir/dex"
cat > "$probe_dir/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.linguize.clipboardprobe">
    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35" />
    <application android:label="Clipboard fixture">
        <activity android:name=".SeedActivity" android:exported="true" />
    </application>
</manifest>
XML
cat > "$probe_dir/SeedActivity.java" <<'JAVA'
package com.linguize.clipboardprobe;
public class SeedActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
            android.content.ClipData.newPlainText("probe", "Bon dia. Probe only."));
        startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
        finish();
    }
}
JAVA
javac -source 8 -target 8 -classpath "$platform" -d "$probe_dir/classes" "$probe_dir/SeedActivity.java"
"$build_tools/d8" --lib "$platform" --output "$probe_dir/dex" "$probe_dir/classes/com/linguize/clipboardprobe/SeedActivity.class"
"$build_tools/aapt" package -f -M "$probe_dir/AndroidManifest.xml" -I "$platform" -F "$probe_dir/seed.apk"
zip -j "$probe_dir/seed.apk" "$probe_dir/dex/classes.dex"
"$build_tools/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$probe_dir/seed.apk"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r "$probe_dir/seed.apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W -n com.linguize.clipboardprobe/.SeedActivity
sleep 2
adb shell settings put secure enabled_accessibility_services com.evanaronson.linguize/.accessibility.ClipboardProbeService
adb shell settings put secure accessibility_enabled 1
for attempt in $(seq 1 30); do
    if adb shell run-as com.evanaronson.linguize cat files/clipboard-probe.txt > "$probe_dir/result.txt" 2>/dev/null; then
        break
    fi
    sleep 1
done
cat "$probe_dir/result.txt"
grep -q 'matches=true' "$probe_dir/result.txt"
# The source is not readable until our actual overlay has focus.
grep -q 'before=NoText' "$probe_dir/result.txt"
adb shell settings put secure enabled_accessibility_services ''
adb shell settings put secure accessibility_enabled 0
