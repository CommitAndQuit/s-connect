package com.suzuki.sconnect.data.model;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * P2 — Trip Recording
 *
 * Realm model representing a single recorded ride.
 * Stored per-trip: start/end metadata, distance, speed stats, and fuel consumed.
 *
 * Status values:
 *   "IN_PROGRESS" — trip is actively being recorded
 *   "COMPLETED"   — trip was stopped or destination reached
 */
public class TripRecord extends RealmObject {

    /** Unique ID — UUID string set when the trip is created. */
    @PrimaryKey
    private String id;

    // ── Timestamps ────────────────────────────────────────────────────────────────────────
    /** Start time in epoch milliseconds. */
    private long startTimeMs;
    /** End time in epoch milliseconds. 0 if trip is still IN_PROGRESS. */
    private long endTimeMs;

    // ── Route ─────────────────────────────────────────────────────────────────────────────
    private String startPlaceName;
    private String endPlaceName;

    // ── Stats ─────────────────────────────────────────────────────────────────────────────
    /** Total distance in kilometres (from ODO delta). */
    private float distanceKm;
    /** Top speed recorded during the trip in km/h. */
    private int topSpeedKmh;
    /** Average speed across the trip in km/h (from running computation). */
    private float avgSpeedKmh;
    /** Estimated fuel consumed in litres (accumulated from byte 25-27 parsing). */
    private float fuelConsumedLitres;

    // ── Status ────────────────────────────────────────────────────────────────────────────
    private String status; // "IN_PROGRESS" | "COMPLETED"

    // ── Constructors ──────────────────────────────────────────────────────────────────────
    // Realm requires a public no-arg constructor
    public TripRecord() {}

    // ── Getters / Setters ─────────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public long getStartTimeMs() { return startTimeMs; }
    public void setStartTimeMs(long startTimeMs) { this.startTimeMs = startTimeMs; }

    public long getEndTimeMs() { return endTimeMs; }
    public void setEndTimeMs(long endTimeMs) { this.endTimeMs = endTimeMs; }

    public String getStartPlaceName() { return startPlaceName; }
    public void setStartPlaceName(String startPlaceName) { this.startPlaceName = startPlaceName; }

    public String getEndPlaceName() { return endPlaceName; }
    public void setEndPlaceName(String endPlaceName) { this.endPlaceName = endPlaceName; }

    public float getDistanceKm() { return distanceKm; }
    public void setDistanceKm(float distanceKm) { this.distanceKm = distanceKm; }

    public int getTopSpeedKmh() { return topSpeedKmh; }
    public void setTopSpeedKmh(int topSpeedKmh) { this.topSpeedKmh = topSpeedKmh; }

    public float getAvgSpeedKmh() { return avgSpeedKmh; }
    public void setAvgSpeedKmh(float avgSpeedKmh) { this.avgSpeedKmh = avgSpeedKmh; }

    public float getFuelConsumedLitres() { return fuelConsumedLitres; }
    public void setFuelConsumedLitres(float fuelConsumedLitres) { this.fuelConsumedLitres = fuelConsumedLitres; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    // ── Convenience ───────────────────────────────────────────────────────────────────────

    /** Duration of the trip in milliseconds. Returns 0 if still in progress. */
    public long getDurationMs() {
        if (endTimeMs > 0 && startTimeMs > 0) return endTimeMs - startTimeMs;
        return 0;
    }

    /**
     * Mileage in km/L. Returns 0 if fuel data is unavailable.
     * Computed from distanceKm / fuelConsumedLitres.
     */
    public float getMileageKmL() {
        if (fuelConsumedLitres > 0) return distanceKm / fuelConsumedLitres;
        return 0;
    }
}
