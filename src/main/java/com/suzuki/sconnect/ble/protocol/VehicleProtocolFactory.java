package com.suzuki.sconnect.ble.protocol;

public class VehicleProtocolFactory {
    public static VehicleProtocol getProtocol(String brandName) {
        // Since we are refactoring to make it generic, we'll start with Suzuki as the default
        // Later we can add more brands like "Honda", "Yamaha", etc.
        if (brandName != null && brandName.equalsIgnoreCase("suzuki")) {
            return new SuzukiProtocol();
        }
        // Default to Suzuki if not specified or unknown
        return new SuzukiProtocol();
    }
}
