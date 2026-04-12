package com.suzuki.sconnect.data.model;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * P5 — Daily Fuel Record
 * 
 * Realm model representing aggregated fuel consumption for a single day.
 * Used to populate the Fuel Economy charts (Daily/Weekly/Monthly).
 */
public class DailyFuelRecord extends RealmObject {

    /** 
     * Primary Key: Date string in "yyyy-MM-dd" format.
     * Ensures one record per day.
     */
    @PrimaryKey
    private String date;

    /** Total distance covered during this day in kilometres. */
    private float totalDistanceKm;

    /** Total fuel consumed during this day in litres. */
    private float totalFuelLitres;

    // ── Constructors ──────────────────────────────────────────────────────────────────────
    public DailyFuelRecord() {}

    public DailyFuelRecord(String date) {
        this.date = date;
        this.totalDistanceKm = 0.0f;
        this.totalFuelLitres = 0.0f;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────────────────

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public float getTotalDistanceKm() { return totalDistanceKm; }
    public void setTotalDistanceKm(float totalDistanceKm) { this.totalDistanceKm = totalDistanceKm; }

    public float getTotalFuelLitres() { return totalFuelLitres; }
    public void setTotalFuelLitres(float totalFuelLitres) { this.totalFuelLitres = totalFuelLitres; }

    // ── Logic ─────────────────────────────────────────────────────────────────────────────

    /**
     * Calculates average mileage for the day in km/L.
     * Returns 0 if no fuel was consumed.
     */
    public float getAverageMileage() {
        if (totalFuelLitres > 0) {
            return totalDistanceKm / totalFuelLitres;
        }
        return 0;
    }

    /**
     * Increments the daily totals.
     */
    public void addProgress(float distance, float fuel) {
        this.totalDistanceKm += distance;
        this.totalFuelLitres += fuel;
    }
}
