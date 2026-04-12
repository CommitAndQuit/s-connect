package com.suzuki.sconnect.utils;

import com.mappls.sdk.services.api.directions.models.LegStep;
import com.mappls.sdk.services.api.directions.models.StepManeuver;
import com.mappls.sdk.geojson.Point;

import org.junit.Test;
import static org.junit.Assert.*;
import org.mockito.Mockito;

public class RoundaboutAngleCalculatorTest {

    @Test
    public void testFallbackBearingCalculations() {
        // Just mock some basic maneuvers to test the fallback calculations
        LegStep step1 = Mockito.mock(LegStep.class);
        LegStep step2 = Mockito.mock(LegStep.class);

        StepManeuver maneuver1 = Mockito.mock(StepManeuver.class);
        StepManeuver maneuver2 = Mockito.mock(StepManeuver.class);

        Mockito.when(step1.maneuver()).thenReturn(maneuver1);
        Mockito.when(step2.maneuver()).thenReturn(maneuver2);

        // Point 1: 0,0
        Point p1 = Point.fromLngLat(0, 0);
        // Point 2: 0,1 (Due North, bearing 0 from 0,0)
        Point p2 = Point.fromLngLat(0, 1);

        Mockito.when(maneuver1.location()).thenReturn(p1);
        Mockito.when(maneuver2.location()).thenReturn(p2);

        Mockito.when(maneuver1.bearingBefore()).thenReturn(90.0);

        // Target is directly north (bearing 0).
        // Before was 90 (East).
        // Difference is -90 + 180 = 90

        int maneuverId = RoundaboutAngleCalculator.getManeuverId(step1, step2);

        // Angle should be 90 -> 90 maps to 66
        assertEquals(66, maneuverId);
    }
}
