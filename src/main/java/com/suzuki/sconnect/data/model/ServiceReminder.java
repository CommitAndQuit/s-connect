package com.suzuki.sconnect.data.model;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * Realm model for storing periodic service reminder data.
 */
public class ServiceReminder extends RealmObject {
    @PrimaryKey
    private String id;
    private String name;
    private long lastServiceDate;      // timestamp in ms
    private int lastServiceOdometer;   // in km
    private int intervalDays;          // days until next service
    private int intervalKm;            // odometer distance until next service
    private boolean isEnabled;         // whether the reminder is active
    private String iconName;           // name of the icon to display

    public ServiceReminder() {}

    public ServiceReminder(String id, String name, int intervalDays, int intervalKm, String iconName) {
        this.id = id;
        this.name = name;
        this.intervalDays = intervalDays;
        this.intervalKm = intervalKm;
        this.iconName = iconName;
        this.isEnabled = true;
        this.lastServiceDate = System.currentTimeMillis();
        this.lastServiceOdometer = 0;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getLastServiceDate() { return lastServiceDate; }
    public void setLastServiceDate(long lastServiceDate) { this.lastServiceDate = lastServiceDate; }

    public int getLastServiceOdometer() { return lastServiceOdometer; }
    public void setLastServiceOdometer(int lastServiceOdometer) { this.lastServiceOdometer = lastServiceOdometer; }

    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }

    public int getIntervalKm() { return intervalKm; }
    public void setIntervalKm(int intervalKm) { this.intervalKm = intervalKm; }

    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean enabled) { isEnabled = enabled; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    /**
     * Helper to calculate the target date for the next service.
     */
    public long getNextServiceDate() {
        return lastServiceDate + (intervalDays * 24L * 60L * 60L * 1000L);
    }

    /**
     * Helper to calculate the target odometer for the next service.
     */
    public int getNextServiceOdometer() {
        return lastServiceOdometer + intervalKm;
    }
}
