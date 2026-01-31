# Build Instructions - Terminal Only

## Prerequisites
- Java JDK 17 (already installed: OpenJDK 17.0.17)
- Android SDK (already configured at: `/home/bhaskar/Android/Sdk`)

## Build Steps

### 1. Build the Debug APK
```bash
cd /home/bhaskar/Kiro/s-connect
/tmp/gradle-8.0/bin/gradle assembleDebug --no-daemon
```

**Build time**: ~2.5 minutes on first build

### 2. Locate the APK
The debug APK will be created at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on Device

**Option A: Via ADB (USB)**
```bash
# Enable USB debugging on your Android device first
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Option B: Manual Transfer**
```bash
# Copy APK to your device
cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/
# Then transfer to phone via USB/Bluetooth and install
```

**Option C: ADB over WiFi**
```bash
# Connect device to same WiFi network
# Enable wireless debugging on device (Android 11+)
# Get IP address from device settings
adb connect <device-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Build Variants

### Debug Build (Default)
```bash
/tmp/gradle-8.0/bin/gradle assembleDebug
```
- Includes debug symbols
- Not optimized
- Larger file size
- Easier to debug

### Release Build (Production)
```bash
/tmp/gradle-8.0/bin/gradle assembleRelease
```
- Optimized
- Smaller file size
- Requires signing key for distribution

## Clean Build
If you need to rebuild from scratch:
```bash
/tmp/gradle-8.0/bin/gradle clean
/tmp/gradle-8.0/bin/gradle assembleDebug
```

## Troubleshooting

### Build Fails
- Check Java version: `java -version` (should be 17+)
- Verify Android SDK path in `local.properties`
- Clean and rebuild: `gradle clean assembleDebug`

### Permission Denied
```bash
chmod +x /tmp/gradle-8.0/bin/gradle
```

### Out of Memory
Add to `gradle.properties`:
```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

## Build Output Summary

**Build Result**: ✅ BUILD SUCCESSFUL in 2m 29s
**Tasks Executed**: 30 actionable tasks
**Output APK**: `app/build/outputs/apk/debug/app-debug.apk`
**APK Size**: ~2-3 MB (debug build)

## Next Steps

1. **Install APK** on your Android device
2. **Grant Permissions** (Bluetooth, Location)
3. **Turn on vehicle cluster**
4. **Launch app** and click "Scan for Devices"
5. **Monitor logs** in the app's live log viewer to debug the connection sequence
