package com.suzuki.sconnect.utils;

import com.mappls.sdk.services.api.directions.models.DirectionsRoute;

public class NavigationStateHolder {
    private static NavigationStateHolder instance;
    private DirectionsRoute currentRoute;
    private String destinationName;
    private String originName;

    private NavigationStateHolder() {
    }

    public static synchronized NavigationStateHolder getInstance() {
        if (instance == null) {
            instance = new NavigationStateHolder();
        }
        return instance;
    }

    public void setCurrentRoute(DirectionsRoute route) {
        this.currentRoute = route;
    }

    public DirectionsRoute getCurrentRoute() {
        return currentRoute;
    }

    public void setOriginDestinationNames(String origin, String destination) {
        this.originName = origin;
        this.destinationName = destination;
    }

    public String getOriginName() {
        return originName;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void clear() {
        currentRoute = null;
        originName = null;
        destinationName = null;
    }
}
