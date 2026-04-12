package com.suzuki.sconnect.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class VehicleStateHolder {
    private static VehicleStateHolder instance;
    private static final String PREF_NAME = "VehicleState";

    private int speed = 0;
    private int odometer = 0;
    private float tripA = 0;
    private float tripB = 0;
    private char gear = 'N';
    private int fuelLevel = 0;
    private float mileageKmL = 0;
    private boolean hasData = false;

    private VehicleStateHolder() {
    }

    public static synchronized VehicleStateHolder getInstance() {
        if (instance == null) {
            instance = new VehicleStateHolder();
        }
        return instance;
    }

    public void loadState(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.speed = 0; // Don't persist speed, should always start at 0
        this.odometer = prefs.getInt("odometer", 0);
        this.tripA = prefs.getFloat("tripA", 0);
        this.tripB = prefs.getFloat("tripB", 0);
        this.gear = (char) prefs.getInt("gear", 'N');
        this.fuelLevel = prefs.getInt("fuelLevel", 0);
        this.mileageKmL = prefs.getFloat("mileageKmL", 0);
        this.hasData = prefs.getBoolean("hasData", false);
    }

    public void updateState(Context context, int speed, int odometer, float tripA, float tripB, char gear, int fuelLevel, float mileageKmL) {
        this.speed = speed;
        this.odometer = odometer;
        this.tripA = tripA;
        this.tripB = tripB;
        this.gear = gear;
        this.fuelLevel = fuelLevel;
        this.mileageKmL = mileageKmL;
        this.hasData = true;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("odometer", odometer)
                .putFloat("tripA", tripA)
                .putFloat("tripB", tripB)
                .putInt("gear", (int) gear)
                .putInt("fuelLevel", fuelLevel)
                .putFloat("mileageKmL", mileageKmL)
                .putBoolean("hasData", true)
                .apply();
    }

    public int getSpeed() { return speed; }
    public int getOdometer() { return odometer; }
    public float getTripA() { return tripA; }
    public float getTripB() { return tripB; }
    public char getGear() { return gear; }
    public int getFuelLevel() { return fuelLevel; }
    public float getMileageKmL() { return mileageKmL; }
    public boolean hasData() { return hasData; }

    public void clear(Context context) {
        speed = 0;
        odometer = 0;
        tripA = 0;
        tripB = 0;
        gear = 'N';
        fuelLevel = 0;
        mileageKmL = 0;
        hasData = false;

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
