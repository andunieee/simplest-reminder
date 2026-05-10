set shell := ["bash", "-euo", "pipefail", "-c"]

package := "com.andunie.reminder"
apk := "app/build/outputs/apk/release/app-release.apk"

# List recipes
default:
    @just --list

# Build the release APK (signed with the debug key)
build:
    ./gradlew assembleRelease

# Build, then install on every connected device
install: build install-only

# Install the already-built APK on every connected device, in parallel
install-only:
    #!/usr/bin/env bash
    set -uo pipefail
    serials=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ -n "$serials" ] || { echo "no devices connected" >&2; exit 1; }
    pids=()
    for s in $serials; do
        ( out=$(adb -s "$s" install -r "{{apk}}" 2>&1) && echo "✓ $s" || { echo "✗ $s: $out" >&2; exit 1; } ) &
        pids+=($!)
    done
    failed=0
    for p in "${pids[@]}"; do wait "$p" || failed=1; done
    exit $failed

# Install and open the app on every connected device
run: install
    for s in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do \
        adb -s "$s" shell am start -n {{package}}/.MainActivity >/dev/null; \
    done

# Uninstall from every connected device
uninstall:
    for s in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do \
        adb -s "$s" uninstall {{package}} || true; \
    done

# Show connected devices
devices:
    adb devices -l

# Follow the app's logs (pass a serial when several devices are connected)
logs serial="":
    adb {{ if serial == "" { "" } else { "-s " + serial } }} logcat -s SimpleReminder

# Show pending alarms on every connected device
alarms:
    for s in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do \
        echo "== $s"; adb -s "$s" shell dumpsys alarm | grep -A3 {{package}} || echo "(none)"; \
    done
