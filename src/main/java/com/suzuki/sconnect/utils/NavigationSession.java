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
        void onNavigationUpdate(int distanceMeters, int turnIconId, String instruction);

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

        int iconId = mapManeuverToIcon(currentStep.maneuver().type(), currentStep.maneuver().modifier());
        String instruction = currentStep.maneuver().instruction();

        if (instruction == null)
            instruction = currentStep.name();
        if (instruction == null)
            instruction = "Turn";

        if (listener != null) {
            listener.onNavigationUpdate((int) distanceToStepEnd, iconId, instruction);
        }
    }

    private int mapManeuverToIcon(String type, String modifier) {
        if (type == null)
            return SuzukiPacketBuilder.TurnIcon.STRAIGHT;

        // Basic mapping logic - Needs to be refined based on actual Mappls strings
        // Common Mappls/OSRM types: "turn", "new name", "depart", "arrive", "merge",
        // "ramp"
        // Modifiers: "left", "right", "sharp left", "slight right", "straight"

        switch (type) {
            case "arrive":
                return SuzukiPacketBuilder.TurnIcon.DESTINATION;
            case "turn":
            case "roundabout": // Simplify roundabouts for now
            case "merge":
            case "fork":
                if (modifier != null) {
                    if (modifier.contains("left")) {
                        if (modifier.contains("sharp"))
                            return SuzukiPacketBuilder.TurnIcon.TURN_LEFT; // Or U-turn?
                        if (modifier.contains("slight"))
                            return SuzukiPacketBuilder.TurnIcon.SLIGHT_LEFT;
                        return SuzukiPacketBuilder.TurnIcon.TURN_LEFT;
                    } else if (modifier.contains("right")) {
                        if (modifier.contains("sharp"))
                            return SuzukiPacketBuilder.TurnIcon.TURN_RIGHT;
                        if (modifier.contains("slight"))
                            return SuzukiPacketBuilder.TurnIcon.SLIGHT_RIGHT;
                        return SuzukiPacketBuilder.TurnIcon.TURN_RIGHT;
                    } else if (modifier.contains("straight")) {
                        return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
                    } else if (modifier.contains("uturn")) {
                        // Heuristic: usually left hand drive vs right? Mappls defaults to local?
                        return SuzukiPacketBuilder.TurnIcon.U_TURN_RIGHT;
                    }
                }
                break;
            case "depart":
                return SuzukiPacketBuilder.TurnIcon.STRAIGHT;
        }

        return SuzukiPacketBuilder.TurnIcon.STRAIGHT; // Default
    }
}
