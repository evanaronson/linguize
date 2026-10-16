#!/usr/bin/env bash
# Real platform boundary: external UID owns clipboard, Settings is foreground, service overlays it.
set -euo pipefail
probe_dir=$(mktemp -d)
helper_package=com.linguize.clipboardprobe
restore_accessibility=false
services_present=false
services_value=
enabled_present=false
enabled_value=

put_secure_setting() {
    # adb joins shell arguments; keep even an empty value as one remote argument.
    local quoted=${2//\'/\'\\\'\'}
    adb shell settings put secure "$1" "'$quoted'"
}

restore_secure_setting() {
    if "$2"; then
        put_secure_setting "$1" "$3"
    else
        adb shell settings delete secure "$1"
    fi
}

cleanup() {
    local status=$? cleanup_failed=0 installed_helpers
    if ! installed_helpers=$(adb shell pm list packages "$helper_package"); then
        echo "Failed to check clipboard helper $helper_package during cleanup" >&2
        cleanup_failed=1
    elif grep -Fxq "package:$helper_package" <<< "$installed_helpers"; then
        if ! adb uninstall "$helper_package" >/dev/null; then
            echo "Failed to uninstall clipboard helper $helper_package" >&2
            cleanup_failed=1
        fi
    fi
    if "$restore_accessibility"; then
        if ! restore_secure_setting enabled_accessibility_services "$services_present" "$services_value"; then
            echo 'Failed to restore enabled_accessibility_services' >&2
            cleanup_failed=1
        fi
        if ! restore_secure_setting accessibility_enabled "$enabled_present" "$enabled_value"; then
            echo 'Failed to restore accessibility_enabled' >&2
            cleanup_failed=1
        fi
    fi
    if ! rm -rf "$probe_dir"; then
        echo 'Failed to remove clipboard probe temporary directory' >&2
        cleanup_failed=1
    fi
    if (( status == 0 && cleanup_failed != 0 )); then
        status=1
    fi
    exit "$status"
}
trap cleanup EXIT
# Snapshot before installation: an already-enabled one-shot probe could bind during install.
# Listing preserves the distinction between an absent key and an empty value.
secure_settings=$(adb shell settings list secure)
while IFS= read -r setting; do
    case "$setting" in
        enabled_accessibility_services=*)
            services_present=true
            services_value=${setting#*=}
            ;;
        accessibility_enabled=*)
            enabled_present=true
            enabled_value=${setting#*=}
            ;;
    esac
done <<< "$secure_settings"
probe_service=com.evanaronson.linguize/.accessibility.ClipboardProbeService
probe_service_full=com.evanaronson.linguize/com.evanaronson.linguize.accessibility.ClipboardProbeService
case ":$services_value:" in
    *":$probe_service:"*|*":$probe_service_full:"*)
        echo 'Disable ClipboardProbeService in Android accessibility settings before running this script; the probe needs a fresh connection.' >&2
        exit 1
        ;;
esac
# Recover from a helper left behind by an earlier run before using a new key.
installed_helpers=$(adb shell pm list packages "$helper_package")
if grep -Fxq "package:$helper_package" <<< "$installed_helpers"; then
    adb uninstall "$helper_package"
fi
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
keytool -genkeypair -keystore "$probe_dir/seed.keystore" -storepass android -keypass android -alias seed -keyalg RSA -keysize 2048 -validity 1 -dname CN=ClipboardProbe
"$build_tools/apksigner" sign --ks "$probe_dir/seed.keystore" --ks-pass pass:android --key-pass pass:android "$probe_dir/seed.apk"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install "$probe_dir/seed.apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W -n com.linguize.clipboardprobe/.SeedActivity
sleep 2
probe_services=${services_value:+$services_value:}$probe_service
# Arm restoration before either write, including a write that changes state then fails.
adb shell run-as com.evanaronson.linguize rm -f files/clipboard-probe.txt
restore_accessibility=true
put_secure_setting enabled_accessibility_services "$probe_services"
put_secure_setting accessibility_enabled 1
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
