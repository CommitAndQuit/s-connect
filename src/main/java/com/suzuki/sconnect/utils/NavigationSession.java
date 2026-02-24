package com.suzuki.sconnect.utils;

import android.location.Location;
import android.util.Log;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.geojson.utils.PolylineUtils;
import com.mappls.sdk.services.api.directions.models.DirectionsRoute;
import com.mappls.sdk.services.api.directions.models.LegStep;
import com.suzuki.sconnect.ble.protocol.SuzukiPacketBuilder;

import java.util.List;

/**
 * Manages the state of an active navigation session.
 * Tracks current step, calculates distance, and determines next instruction.
 */
public class NavigationSession {

    private static final String TAG = "NavigationSession";
    private DirectionsRoute currentRoute;
    private List<LegStep> steps;
    private int currentStepIndex = 0;
    private boolean isNavigating = false;

    public interface NavigationUpdateListener {
        void onNavigationUpdate(int distanceMeters, int turnIconId, String instruction, String etaStr);

        void onDestinationReached();
    }

    private NavigationUpdateListener listener;

    public void startSession(DirectionsRoute route, NavigationUpdateListener listener) {
        this.currentRoute = route;
        this.listener = listener;
        if (route.legs() != null && !route.legs().isEmpty()) {
            // Flatten steps from the first leg (assuming single leg for now)
            this.steps = route.legs().get(0).steps();
            this.currentStepIndex = 0;
            this.isNavigating = true;
            Log.d(TAG, "Navigation Session Started. Total steps: " + (steps != null ? steps.size() : 0));

            // Initial update
            updateNavigationState(null);
        } else {
            Log.e(TAG, "No legs in route!");
        }
    }

    public void stopSession() {
        this.isNavigating = false;
        this.currentRoute = null;
        this.steps = null;
    }

    public void onLocationChanged(Location location) {
        if (!isNavigating || steps == null || steps.isEmpty())
            return;
        updateNavigationState(location);
    }

    private void updateNavigationState(Location currentUserLocation) {
        if (steps == null || steps.isEmpty()) {
            Log.e(TAG, "updateNavigationState: steps null or empty!");
            return;
        }

        if (currentStepIndex >= steps.size()) {
            Log.d(TAG, "updateNavigationState: End reached. index=" + currentStepIndex);
            if (listener != null)
                listener.onDestinationReached();
            isNavigating = false;
            return;
        }

        LegStep currentStep = steps.get(currentStepIndex);
        double distanceToStepEnd = 0;

        Log.d(TAG, "updateNavigationState: Processing step " + currentStepIndex + " (" + currentStep.maneuver().type()
                + ")");

        if (currentUserLocation != null) {
            Point maneuverPoint = currentStep.maneuver().location();
            if (maneuverPoint != null) {
                float[] results = new float[1];
                Location.distanceBetween(currentUserLocation.getLatitude(), currentUserLocation.getLongitude(),
                        maneuverPoint.latitude(), maneuverPoint.longitude(), results);
                distanceToStepEnd = results[0];

                Log.d(TAG, String.format("Dist to maneuver: %.1fm", distanceToStepEnd));
            }

            // Check if we arrived at step (e.g., within 30 meters)
            if (distanceToStepEnd < 30) {
                Log.d(TAG, "Arrived at step " + currentStepIndex + ", advancing...");
                currentStepIndex++;
                if (currentStepIndex < steps.size()) {
                    currentStep = steps.get(currentStepIndex);
                    distanceToStepEnd = currentStep.distance();
                    Log.d(TAG, "New step distance: " + distanceToStepEnd);
                } else {
                    Log.d(TAG, "Destination reached (no more steps)");
                    if (listener != null)
                        listener.onDestinationReached();
                    isNavigating = false;
                    return;
                }
            }
        } else {
            distanceToStepEnd = currentStep.distance();
            Log.d(TAG, "Initial state (no location): dist=" + distanceToStepEnd);
        }

        int iconId = mapManeuverToIcon(currentStep);
        String instruction = currentStep.maneuver().instruction();

        if (instruction == null)
            instruction = currentStep.name();
        if (instruction == null)
            instruction = "Turn";

        // Calculate ETA
        String etaStr = calculateETA();

        if (listener != null) {
            listener.onNavigationUpdate((int) distanceToStepEnd, iconId, instruction, etaStr);
        }
    }

    private String calculateETA() {
        if (steps == null || currentStepIndex >= steps.size())
            return "1200PM";

        double remainingDurationSeconds = 0;
        for (int i = currentStepIndex; i < steps.size(); i++) {
            Double d = steps.get(i).duration();
            if (d != null) {
                remainingDurationSeconds += d;
            }
        }

        long etaMillis = System.currentTimeMillis() + (long) (remainingDurationSeconds * 1000);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(etaMillis);

        int hour = cal.get(java.util.Calendar.HOUR);
        if (hour == 0)
            hour = 12;
        int minute = cal.get(java.util.Calendar.MINUTE);
        String ampm = cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM ? "AM" : "PM";

        return String.format("%02d%02d%s", hour, minute, ampm);
    }

    /**
     * Maps a step's maneuver to the correct Suzuki cluster icon byte.
     *
     * KEY FINDING from decompiled Suzuki source (w0.java):
     * The original app DIRECTLY reads `maneuver.maneuverId()` from the Mappls API
     * JSON (`maneuver_id` field in the route response) and sends that integer
     * as the cluster icon byte at bArr[2] in the navigation packet.
     *
     * Our previous implementation was incorrect — it computed an icon from the
     * type+modifier strings, which produced wrong values (e.g., right when left).
     *
     * Fallback: if `maneuverId()` is null (older API or missing), we fall back to
     * a best-effort string mapping.
     */
    private int mapManeuverToIcon(LegStep step) {
        com.mappls.sdk.services.api.directions.models.StepManeuver maneuver = step.maneuver();
        if (maneuver == null)
            return SuzukiPacketBuilder.TurnIcon.NONE;

        String type = maneuver.type();

        // PRIMARY: Use the official Mappls SDK maneuverId and remap it to Suzuki icon
        // ID
        Integer maneuverId = maneuver.maneuverId();
        if (maneuverId != null && maneuverId >= 0) {
            Log.d(TAG, "Using API maneuverId: " + maneuverId);

            // Bug #4: Handle roundabout exit directional icons
            if (type != null && (type.equalsIgnoreCase("roundabout") || type.equalsIgnoreCase("rotary"))) {
                int roundaboutIcon = calculateRoundaboutIconId(step);
                if (roundaboutIcon != SuzukiPacketBuilder.TurnIcon.NONE) {
                    return roundaboutIcon;
                }
            }

            return SuzukiPacketBuilder.mapMapplsToClusterIcon(maneuverId);
        }

        // FALLBACK: If maneuverId is not provided, use type+modifier strings
        String modifier = maneuver.modifier();
        Log.w(TAG, "maneuverId null, falling back to string mapping: type=" + type + " modifier=" + modifier);

        if (type == null)
            return SuzukiPacketBuilder.TurnIcon.NONE;

        switch (type.toLowerCase()) {
            case "arrive":
                return SuzukiPacketBuilder.TurnIcon.DESTINATION;
            case "depart":
            case "new name":
            case "continue":
                return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
            case "turn":
            case "merge":
            case "fork":
            case "off ramp":
            case "on ramp":
            case "end of road":
                if (modifier == null)
                    return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
                modifier = modifier.toLowerCase();

                // ORDER MATTERS: Specific modifiers must be checked before generic
                // "left"/"right"
                if (modifier.contains("slight left"))
                    return SuzukiPacketBuilder.TurnIcon.SLIGHT_LEFT;
                if (modifier.contains("sharp left"))
                    return SuzukiPacketBuilder.TurnIcon.TURN_LEFT;
                if (modifier.contains("left"))
                    return SuzukiPacketBuilder.TurnIcon.TURN_LEFT;

                if (modifier.contains("slight right"))
                    return SuzukiPacketBuilder.TurnIcon.SLIGHT_RIGHT;
                if (modifier.contains("sharp right"))
                    return SuzukiPacketBuilder.TurnIcon.TURN_RIGHT;
                if (modifier.contains("right"))
                    return SuzukiPacketBuilder.TurnIcon.TURN_RIGHT;

                if (modifier.contains("uturn"))
                    return SuzukiPacketBuilder.TurnIcon.U_TURN_LEFT;
                return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
            case "roundabout":
            case "rotary":
            case "exit roundabout":
            case "roundabout turn":
                return SuzukiPacketBuilder.TurnIcon.ROUNDABOUT;
            default:
                return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
        }
    }

    /**
     * Recalculates the roundabout icon ID based on the exit bearing angle.
     * Ported from decompiled mapping logic (c.I and c.u).
     */
    private int calculateRoundaboutIconId(LegStep currentStep) {
        if (steps == null || currentStepIndex + 1 >= steps.size()) {
            return SuzukiPacketBuilder.TurnIcon.NONE;
        }

        LegStep nextStep = steps.get(currentStepIndex + 1);
        int mapplsId = RoundaboutAngleCalculator.getManeuverId(currentStep, nextStep);

        // 5. Remap to Suzuki cluster icon
        return SuzukiPacketBuilder.mapMapplsToClusterIcon(mapplsId);
    }
}
