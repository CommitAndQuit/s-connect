# IMPORTANT: Installation Instructions

## The Problem
The crash is still happening because you have the **OLD VERSION** installed.

Android won't update the app properly if you just install over it - you MUST uninstall first.

## Solution: Uninstall Old Version First

### Step 1: Uninstall
```bash
adb uninstall com.suzuki.bletest
```

You should see:
```
Success
```

### Step 2: Install New Version
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

You should see:
```
Performing Streamed Install
Success
```

### Step 3: Verify
Check the app version:
```bash
adb shell dumpsys package com.suzuki.bletest | grep versionName
```

## Why This Happens
The Android 14+ fix requires code changes that won't be applied if you just install over the old app. The old code is still running even though you built a new APK.

## Verification
After uninstalling and reinstalling:
1. Launch the app
2. It should NOT crash
3. You should see the main UI with "Scan for Devices" button
4. The log viewer should be at the bottom

## If Still Crashes
If it still crashes after uninstall + reinstall, run:
```bash
adb logcat -c  # Clear logs
adb logcat | grep -A 20 "FATAL EXCEPTION"
```

And share the full stack trace - it might be a different issue.
