package com.mani.orbit;

/** Small, dependency-free geographic calculation used by the location service. */
final class WorkoutLocationMath {
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private WorkoutLocationMath() { }

    static double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
            + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
            * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }
}
