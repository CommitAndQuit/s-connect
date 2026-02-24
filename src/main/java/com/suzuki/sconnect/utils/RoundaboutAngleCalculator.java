package com.suzuki.sconnect.utils;

import com.mappls.sdk.geojson.Point;
import com.mappls.sdk.geojson.utils.PolylineUtils;
import com.mappls.sdk.services.api.directions.models.LegStep;
import com.mappls.sdk.turf.TurfMeasurement;

import java.util.List;

public class RoundaboutAngleCalculator {

    public static int getManeuverId(LegStep currentStep, LegStep nextStep) {
        float angle = calculateAngle(currentStep, nextStep);
        return mapAngleToManeuverId(angle);
    }

    private static float calculateAngle(LegStep legStep, LegStep legStep2) {
        if (legStep == null || legStep.geometry() == null) {
            return fallbackBearing(legStep, legStep2);
        }

        List<Point> decode = PolylineUtils.decode(legStep.geometry(), 6);
        if (decode == null || decode.isEmpty()) {
            return fallbackBearing(legStep, legStep2);
        }

        Point point = decode.get(0);
        Point point2 = decode.size() > 1 ? decode.get(1) : decode.get(0);
        Point point3 = decode.size() % 2 == 0 ? decode.get(decode.size() / 2) : decode.get((decode.size() / 2) + 1);

        double latitude = point.latitude();
        double longitude = point.longitude();
        double latitude2 = point3.latitude();
        double longitude2 = point3.longitude();
        double latitude3 = point2.latitude();
        double longitude3 = point2.longitude();

        double d = longitude2 - longitude3;
        double d2 = longitude3 - longitude;
        double d3 = longitude - longitude2;
        double d4 = ((latitude3 * d3) + (latitude2 * d2) + (latitude * d)) * 2.0d;

        if (d4 == 0.0) {
            return fallbackBearing(legStep, legStep2);
        }

        double d5 = (longitude * longitude) + (latitude * latitude);
        double d6 = (longitude2 * longitude2) + (latitude2 * latitude2);
        double d7 = (longitude3 * longitude3) + (latitude3 * latitude3);
        double d8 = ((d3 * d7) + ((d2 * d6) + (d * d5))) / d4;
        double d9 = (((latitude2 - latitude) * d7) + (((latitude - latitude3) * d6) + ((latitude3 - latitude2) * d5)))
                / d4;

        double radians = Math.toRadians(point.latitude());
        double radians2 = Math.toRadians(point.longitude());
        double radians3 = Math.toRadians(point3.latitude());
        Math.acos((Math.cos(radians2 - Math.toRadians(point3.longitude())) * Math.cos(radians3) * Math.cos(radians))
                + (Math.sin(radians3) * Math.sin(radians)));

        double radians4 = Math.toRadians(point3.latitude());
        double radians5 = Math.toRadians(point3.longitude());
        double radians6 = Math.toRadians(point2.latitude());
        Math.acos((Math.cos(radians5 - Math.toRadians(point2.longitude())) * Math.cos(radians6) * Math.cos(radians4))
                + (Math.sin(radians6) * Math.sin(radians4)));

        double radians7 = Math.toRadians(point.latitude());
        double radians8 = Math.toRadians(point.longitude());
        double radians9 = Math.toRadians(point2.latitude());
        double acos = (Math.acos(
                (Math.cos(radians8 - Math.toRadians(point2.longitude())) * (Math.cos(radians9) * Math.cos(radians7)))
                        + (Math.sin(radians9) * Math.sin(radians7)))
                * 6371.01d) / 2.0d;

        double radians10 = Math.toRadians(d8);
        double radians11 = Math.toRadians(d9);
        double radians12 = Math.toRadians(point.latitude());
        double acos2 = Math.acos(
                (Math.cos(radians11 - Math.toRadians(point.longitude())) * Math.cos(radians12) * Math.cos(radians10))
                        + (Math.sin(radians12) * Math.sin(radians10)))
                * 6371.01d;

        double radians13 = Math.toRadians(d8);
        double radians14 = Math.toRadians(d9);
        double radians15 = Math.toRadians(point3.latitude());
        double acos3 = Math.acos(
                (Math.cos(radians14 - Math.toRadians(point3.longitude())) * Math.cos(radians15) * Math.cos(radians13))
                        + (Math.sin(radians15) * Math.sin(radians13)))
                * 6371.01d;

        double d10 = acos / acos2;
        if (d10 > 1.0d) {
            d10 = 1.0d;
        } else if (d10 < -1.0d) {
            d10 = -1.0d;
        }

        double acos4 = (Math.acos(d10) * 180.0d) / Math.PI;

        double fromLng = (point.longitude() > point2.longitude() ? point.longitude() : point2.longitude())
                - (Math.abs(point.longitude() - point2.longitude()) / 2.0d);
        double fromLat = (point.latitude() > point2.latitude() ? point.latitude() : point2.latitude())
                - (Math.abs(point.latitude() - point2.latitude()) / 2.0d);
        Point fromLngLat = Point.fromLngLat(fromLng, fromLat);

        double radians16 = Math.toRadians(point3.latitude());
        double radians17 = Math.toRadians(point3.longitude());
        double radians18 = Math.toRadians(fromLngLat.latitude());
        double acos5 = Math.acos((Math.cos(radians17 - Math.toRadians(fromLngLat.longitude())) * Math.cos(radians18)
                * Math.cos(radians16)) + (Math.sin(radians18) * Math.sin(radians16))) * 6371.01d;

        double d11 = 180.0d - (acos4 * 2.0d);

        if (!Double.isNaN(d11)) {
            return acos5 >= acos3 ? normalizeAngle((float) (360.0d - d11)) : normalizeAngle((float) d11);
        }

        return fallbackBearing(legStep, legStep2);
    }

    private static float fallbackBearing(LegStep legStep, LegStep legStep2) {
        if (legStep2 == null || legStep == null || legStep.maneuver() == null || legStep2.maneuver() == null) {
            return 0.0f;
        }
        Point location = legStep.maneuver().location();
        Point location2 = legStep2.maneuver().location();

        double bearing = TurfMeasurement.bearing(location, location2);
        if (bearing < 0.0d) {
            bearing += 360.0d;
        }
        double doubleValue = legStep.maneuver().bearingBefore() != null
                ? legStep.maneuver().bearingBefore().doubleValue()
                : 0.0;
        if (doubleValue < 0.0d) {
            doubleValue += 360.0d;
        }
        double d12 = (bearing - doubleValue) + 180.0d;
        if (d12 < 0.0d) {
            d12 += 360.0d;
        }
        if (d12 > 360.0d) {
            d12 -= 360.0d;
        }
        return (float) d12;
    }

    private static int normalizeAngle(float f) {
        if (f <= 45.0f) {
            return 45;
        }
        if (f <= 90.0f) {
            return 90;
        }
        if (f <= 135.0f) {
            return 135;
        }
        if (f <= 180.0f) {
            return 180;
        }
        if (f <= 225.0f) {
            return 225;
        }
        return f <= 270.0f ? 270 : 315;
    }

    private static int mapAngleToManeuverId(float f) {
        if (f <= 45.0f) {
            return 65;
        }
        if (f <= 90.0f) {
            return 66;
        }
        if (f <= 135.0f) {
            return 67;
        }
        if (f <= 180.0f) {
            return 68;
        }
        if (f <= 225.0f) {
            return 69;
        }
        return f <= 270.0f ? 70 : 71;
    }
}
