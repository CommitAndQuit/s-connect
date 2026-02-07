package com.suzuki.sconnect.utils;

import android.util.Log;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.services.api.OnResponseCallback;
import com.mappls.sdk.services.api.directions.DirectionsCriteria;
import com.mappls.sdk.services.api.directions.MapplsDirections;
import com.mappls.sdk.services.api.directions.MapplsDirectionManager;
import com.mappls.sdk.services.api.directions.models.DirectionsResponse;

import java.util.List;

/**
 * Manages fetching routes from Mappls API.
 */
public class MapplsRouteManager {

    private static final String TAG = "MapplsRouteManager";

    public interface RouteCallback {
        void onRouteSuccess(DirectionsResponse response);

        void onRouteStringError(String error); // Renamed to avoid method clash if basic types match
    }

    public void fetchRoute(Point origin, Point destination, RouteCallback callback) {
        // Use MapplsDirections.builder() to build the request
        MapplsDirections.Builder builder = MapplsDirections.builder()
                .origin(origin)
                .destination(destination)
                .profile(DirectionsCriteria.PROFILE_DRIVING)
                .steps(true) // Start fetching Steps for TBT
                .alternatives(true)
                .overview(DirectionsCriteria.OVERVIEW_FULL);

        MapplsDirectionManager.newInstance(builder.build()).call(new OnResponseCallback<DirectionsResponse>() {
            @Override
            public void onSuccess(DirectionsResponse directionsResponse) {
                if (directionsResponse != null && directionsResponse.routes() != null
                        && !directionsResponse.routes().isEmpty()) {
                    Log.d(TAG, "Route fetched successfully: " + directionsResponse.routes().size() + " routes");
                    callback.onRouteSuccess(directionsResponse);
                } else {
                    Log.e(TAG, "Route fetched but response is empty");
                    callback.onRouteStringError("Empty route response");
                }
            }

            @Override
            public void onError(int code, String message) {
                Log.e(TAG, "Route fetch failed: " + code + " - " + message);
                callback.onRouteStringError(message);
            }
        });
    }
}
