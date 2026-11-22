#!/bin/bash
set -eu
python3 ci/fixture.py > "$RUNNER_TEMP/fixture.log" 2>&1 &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.jiyu.movie ACTIVATE_VPN allow
adb shell am start -n com.jiyu.movie/.MainActivity
sleep 3
adb shell am start -n com.jiyu.movie/.ProbeActivity
for i in $(seq 1 65); do
    logs=$(adb logcat -d -s LemonSmoke:I)
    if [[ "$logs" == *PASSED* ]]; then
        echo "Automatic connection and manual route passed"
        adb shell input keyevent KEYCODE_BACK
        sleep 2
        adb shell input keyevent KEYCODE_HOME
        sleep 5
        before=$(grep -c '/nodes.yaml' "$RUNNER_TEMP/fixture.log")
        adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.jiyu.movie/.MainActivity
        for n in $(seq 1 20); do
            if [[ $(grep -c '/nodes.yaml' "$RUNNER_TEMP/fixture.log") -gt "$before" ]]; then exit 0; fi
            sleep 1
        done
        break
    fi
    if [[ "$logs" == *FAILED* ]]; then break; fi
    sleep 2
done
cat "$RUNNER_TEMP/fixture.log" | head -c 3000
adb shell dumpsys activity services com.jiyu.movie | head -60
adb shell dumpsys activity activities | grep mResumedActivity || true
adb logcat -d | grep -E "LemonSmoke|AndroidRuntime|F DEBUG|panic:|VpnManagerService|GoLog|ClashMetaForAndroid|UnsatisfiedLinkError" | tail -150
exit 1
