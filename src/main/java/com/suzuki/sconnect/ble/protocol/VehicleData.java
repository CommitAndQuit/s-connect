package com.suzuki.sconnect.ble.protocol;

public class VehicleData {
    public int speed; // in km/h
    public int odometer; // in km
    public float tripA; // in km
    public float tripB; // in km
    public char gear; // 'N', '1', '2', etc.
    public int fuelLevel; // 1-6 bars
    public float fuelConsumption; // litres per cluster update
    public boolean isValid;

    @Override
    public String toString() {
        return String.format(
                "Speed: %d km/h, ODO: %d km, Trip A: %.1f km, Trip B: %.1f km, Gear: %c, Fuel: %d bars, FC: %.4f L",
                speed, odometer, tripA, tripB, gear, fuelLevel, fuelConsumption);
    }
}
