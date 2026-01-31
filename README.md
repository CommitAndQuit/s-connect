# Suzuki BLE Debug Test App

A comprehensive debugging tool for connecting to Suzuki vehicle instrument clusters via Bluetooth Low Energy (BLE).

## Features

- **Extensive Debug Logging**: Every BLE operation is logged with timestamps and detailed information
- **Live Log Viewer**: Real-time log display in the app UI
- **Packet Inspection**: Hex and ASCII display of all sent and received packets
- **State Machine Tracking**: Visual tracking of connection states
- **Service/Characteristic Discovery**: Automatic enumeration of all GATT services
- **Vehicle Data Display**: Real-time display of ODO, Trip, Gear, and Fuel data

## Building the App

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK 26 (Android 8.0) or higher
- Physical Android device with BLE support (emulator won't work for BLE)

### Build Steps

1. **Copy the app folder** to your Android Studio projects directory

2. **Create a `settings.gradle` file** in the parent directory:
```gradle
include ':app'
```

3. **Create a `build.gradle` file** in the parent directory:
```gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

4. **Open the project in Android Studio**

5. **Sync Gradle** and build the project

6. **Connect your Android device** via USB with Developer Mode enabled

7. **Run the app** on your device

## Using the App

### Step 1: Grant Permissions
When you first launch the app, it will request Bluetooth and Location permissions. Grant all requested permissions.

### Step 2: Scan for Devices
1. Make sure your Suzuki vehicle cluster is powered on
2. Click "Scan for Devices"
3. Wait 10 seconds for the scan to complete
4. The app will automatically detect Suzuki devices (AS/BS/BM prefixes)

### Step 3: Connect
1. Enter a username (max 22 characters) - this will be sent in the ?6 identification packet
2. Click "Connect"
3. Watch the debug logs to see the connection sequence:
   - GATT connection
   - Service discovery
   - MTU negotiation (250 bytes)
   - Notification enablement
   - Identification packets (4x ?6)
   - Heartbeat packets (1/second ?3)
   - Vehicle data packets (?7)

### Step 4: Monitor
- **Status**: Shows current connection state
- **Device Info**: Shows device name, address, and RSSI
- **Vehicle Data**: Shows real-time ODO, Trip, Gear, and Fuel readings
- **Debug Logs**: Shows all BLE operations with timestamps

## Debug Log Format

Logs are formatted as:
```
[HH:mm:ss.SSS] LEVEL/TAG: message
```

**Log Levels:**
- `D` = Debug (detailed operation info)
- `I` = Info (important events)
- `W` = Warning (potential issues)
- `E` = Error (failures)

**Key Tags:**
- `Scanner` = BLE device scanning
- `GATT` = GATT operations (connect, discover, read, write)
- `PacketBuilder` = Packet construction
- `PacketParser` = Packet parsing
- `Service` = Foreground service lifecycle
- `STATE` = Connection state transitions

## Troubleshooting

### No devices found
- Check that the vehicle cluster is powered on
- Verify Bluetooth is enabled on your phone
- Check that Location permission is granted (required for BLE scanning)
- Look for log entries with tag `Scanner` to see what devices were found

### Connection fails
- Check the debug logs for the exact failure point
- Look for `GATT` tag entries to see where the connection sequence failed
- Common issues:
  - Service[3] not found → Device has different GATT structure
  - MTU negotiation failed → May need to use default MTU (23 bytes)
  - Notification enable failed → Check CCCD descriptor

### No vehicle data received
- Verify that all 4 identification packets (?6) were sent successfully
- Check that heartbeat packets (?3) are being sent every second
- Look for `PacketParser` entries to see if ?7 packets are being received
- Verify the checksum calculation matches your vehicle model (AS vs BS/BM)

### Checksum errors
- Check the model detection logic in the logs
- AS models use Direct Sum checksum
- BS/BM models use Inverse Sum (bitwise NOT) checksum
- Look for `Checksum` tag entries to see the calculation details

## Log Analysis

### Successful Connection Sequence
```
[TIME] I/Scanner: Found device: _BM01... (RSSI: -45 dBm)
[TIME] I/GATT: Connected to GATT server
[TIME] I/GATT: Found 4 services
[TIME] I/GATT: Using Service[3]: 0000fff0-...
[TIME] I/GATT: MTU changed to: 250
[TIME] I/GATT: Notifications enabled successfully
[TIME] I/Service: Sending ?6 packet 1/4
[TIME] I/Service: Sending ?6 packet 2/4
[TIME] I/Service: Sending ?6 packet 3/4
[TIME] I/Service: Sending ?6 packet 4/4
[TIME] I/Service: Starting Heartbeat
[TIME] I/PacketParser: Successfully parsed vehicle data: ODO: 2437 km...
```

## Packet Inspection

All packets are logged in both HEX and ASCII format:
```
[TIME] I/PacketBuilder: Built ?6 packet [30 bytes]
HEX:  A5 36 54 45 53 54 00 00 ... 52 A1 7F
ASCII: .6TEST............R..
```

This allows you to:
- Verify packet structure
- Check header (0xA5) and footer (0x7F)
- Inspect payload content
- Validate checksums

## Known Limitations

- Requires physical Android device (BLE doesn't work in emulator)
- Minimum Android 8.0 (API 26)
- Only tested with Suzuki SBM clusters
- Heartbeat uses fixed values (speed=0, signal=4)

## Next Steps

If the app successfully connects and receives data, you can:
1. Modify packet construction in `SuzukiPacketBuilder.java`
2. Add custom data parsing in `SuzukiPacketParser.java`
3. Implement additional packet types (?1, ?4, ?5)
4. Add navigation or notification features

## License

This is a debug/test tool for research purposes.
