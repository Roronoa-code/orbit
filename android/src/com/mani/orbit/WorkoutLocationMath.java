package com.mani.orbit;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small, dependency-free geographic calculation used by the location service. */
final class WorkoutLocationMath {
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private double anchorLat, anchorLon, anchorAccuracy, smoothedSpeed;
    private long anchorTime, lastGood, movingSince;

    void reset() { anchorTime = lastGood = movingSince = 0; smoothedSpeed = 0; }
    long lastGood() { return lastGood; }

    static final class Fix {
        final double distance, speed;
        final boolean breakBefore;
        Fix(double distance, double speed, boolean breakBefore) { this.distance = distance; this.speed = speed; this.breakBefore = breakBefore; }
    }

    /** Accuracy is a radius, not movement. Require displacement outside that uncertainty. */
    Fix filter(String kind, long now, long at, double lat, double lon, double accuracy, Double speed, Double speedAccuracy) {
        double ceiling = "Walking".equals(kind) ? 4 : "Running".equals(kind) ? 12 : 30;
        if (at <= 0 || at > now || now - at > 10_000_000_000L || at <= lastGood
                || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || !Double.isFinite(accuracy) || accuracy <= 0 || accuracy > 25
                || speed != null && (!Double.isFinite(speed) || speed < 0 || speed > ceiling)) return null;
        boolean fresh = anchorTime == 0 || at - lastGood > 15_000_000_000L;
        if (fresh) {
            anchorLat = lat; anchorLon = lon; anchorAccuracy = accuracy; anchorTime = at; lastGood = at;
            movingSince = 0; smoothedSpeed = 0; return new Fix(0, 0, true);
        }
        double distance = distanceMeters(anchorLat, anchorLon, lat, lon), seconds = (at - anchorTime) / 1e9;
        if (seconds <= 0 || distance / seconds > ceiling) return null;
        lastGood = at;
        boolean measured = speed != null && speedAccuracy != null && Double.isFinite(speedAccuracy) && speedAccuracy >= 0 && speedAccuracy <= .5;
        // A reliable zero-speed Doppler fix wins over a wandering indoor position.
        boolean motion = measured ? speed - speedAccuracy > .35 : distance > Math.max(6, anchorAccuracy + accuracy);
        if (!motion) { boolean stopped = smoothedSpeed > 0; movingSince = 0; smoothedSpeed = 0; return stopped ? new Fix(0, 0, false) : null; }
        if (movingSince == 0) { movingSince = at; return null; }
        if (at - movingSince < 1_000_000_000L || distance < Math.max(3, (anchorAccuracy + accuracy) * .5)) return null;
        double current = measured ? speed : distance / seconds;
        smoothedSpeed = smoothedSpeed == 0 ? current : smoothedSpeed + (current - smoothedSpeed) * Math.min(1, seconds / 3);
        anchorLat = lat; anchorLon = lon; anchorAccuracy = accuracy; anchorTime = at;
        return new Fix(distance, smoothedSpeed, false);
    }

    static JSONArray decimate(JSONArray source) throws Exception {
        // ponytail: halve the route at the 4,096-point ceiling; switch to a streamed route store if detail needs to grow.
        JSONArray reduced = new JSONArray();
        boolean breakPending = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject point = source.getJSONObject(i);
            boolean keep = i == 0 || i == source.length() - 1 || i % 2 == 0;
            if (point.optBoolean("breakBefore", false)) breakPending = true;
            if (keep) {
                if (breakPending && i > 0) point = new JSONObject(point.toString()).put("breakBefore", true);
                reduced.put(point);
                breakPending = false;
            }
        }
        return reduced;
    }

    static double distanceMeters(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
            + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
            * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }
}
