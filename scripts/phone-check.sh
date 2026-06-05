#!/usr/bin/env bash
# Phone-checker — replaces the 39K-token subagent with a 500-token script.
# Usage: ./scripts/phone-check.sh <device-serial> <versionCode>
set -euo pipefail

DEVICE="${1:?usage: phone-check.sh <device-serial> <versionCode>}"
EXPECTED_VCODE="${2:?usage: phone-check.sh <device-serial> <versionCode>}"

echo "=== Phone check: $DEVICE ==="

# 1. Version
INFO=$(adb -s "$DEVICE" shell dumpsys package org.techempower.candela | grep -E "versionCode|versionName")
ACTUAL_VCODE=$(echo "$INFO" | grep -oP 'versionCode=\K\d+')
ACTUAL_VNAME=$(echo "$INFO" | grep -oP 'versionName=\K\S+')
echo "Version: $ACTUAL_VNAME (code $ACTUAL_VCODE)"
if [ "$ACTUAL_VCODE" != "$EXPECTED_VCODE" ]; then
    echo "FAIL: expected versionCode=$EXPECTED_VCODE, got $ACTUAL_VCODE"
    exit 1
fi

# 2. Launch
adb -s "$DEVICE" shell am start -n org.techempower.candela/in.jphe.storyvox.MainActivity > /dev/null 2>&1
sleep 3

# 3. Crash check
PID=$(adb -s "$DEVICE" shell pidof org.techempower.candela 2>/dev/null || true)
if [ -z "$PID" ]; then
    echo "FAIL: process not running after launch"
    exit 1
fi

CRASHES=$(adb -s "$DEVICE" logcat -d -t 30 --pid="$PID" 2>/dev/null \
    | grep -ciE 'fatal|crash|java\.lang\.\w+exception' || true)
if [ "$CRASHES" -gt 0 ]; then
    echo "FAIL: $CRASHES crash-related lines in logcat"
    adb -s "$DEVICE" logcat -d -t 30 --pid="$PID" \
        | grep -iE 'fatal|crash|java\.lang\.\w+exception' | head -5
    exit 1
fi

echo "PASS: v$ACTUAL_VNAME running (PID $PID), no crashes"
