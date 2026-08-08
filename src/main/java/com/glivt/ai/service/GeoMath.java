package com.glivt.ai.service;

/** Great-circle and point-to-polyline geometry shared by the AI services. */
public final class GeoMath {

    public static final double EARTH_RADIUS_M = 6_371_008.8;

    private GeoMath() {
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        return haversineMeters(lat1, lon1, lat2, lon2) / 1000.0;
    }

    /** Result of projecting a point onto a polyline. */
    public record Projection(double distanceMeters, double latitude, double longitude,
            int segmentIndex, double segmentFraction) {
    }

    /**
     * Shortest distance from {@code (lat,lng)} to the polyline, plus the nearest
     * point on it.
     *
     * <p>Distances in a fleet corridor are small enough that an equirectangular
     * projection around the query point is accurate to well under a metre, so
     * each segment is solved in a local planar frame rather than with an
     * expensive spherical solution.
     */
    public static Projection distanceToPolyline(double lat, double lng, double[][] path) {
        if (path == null || path.length == 0) {
            return null;
        }
        if (path.length == 1) {
            return new Projection(haversineMeters(lat, lng, path[0][0], path[0][1]),
                    path[0][0], path[0][1], 0, 0.0);
        }

        double cosLat = Math.cos(Math.toRadians(lat));
        double bestDistance = Double.MAX_VALUE;
        double bestLat = path[0][0];
        double bestLng = path[0][1];
        int bestSegment = 0;
        double bestFraction = 0.0;

        for (int i = 0; i < path.length - 1; i++) {
            double aLat = path[i][0];
            double aLng = path[i][1];
            double bLat = path[i + 1][0];
            double bLng = path[i + 1][1];

            // Local planar coordinates in metres relative to the query point.
            double ax = toMetersX(aLng - lng, cosLat);
            double ay = toMetersY(aLat - lat);
            double bx = toMetersX(bLng - lng, cosLat);
            double by = toMetersY(bLat - lat);

            double dx = bx - ax;
            double dy = by - ay;
            double lengthSquared = dx * dx + dy * dy;

            double t = lengthSquared == 0.0 ? 0.0 : -(ax * dx + ay * dy) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));

            double px = ax + t * dx;
            double py = ay + t * dy;
            double distance = Math.hypot(px, py);

            if (distance < bestDistance) {
                bestDistance = distance;
                bestLat = aLat + t * (bLat - aLat);
                bestLng = aLng + t * (bLng - aLng);
                bestSegment = i;
                bestFraction = t;
            }
        }
        return new Projection(bestDistance, bestLat, bestLng, bestSegment, bestFraction);
    }

    /**
     * The point on the polyline, forward of {@code fromSegment}, that a deviating
     * vehicle should aim for to rejoin the corridor. Returns the next vertex
     * ahead of the projection.
     */
    public static double[] reentryPoint(double[][] path, int fromSegment) {
        if (path == null || path.length == 0) {
            return null;
        }
        int next = Math.min(fromSegment + 1, path.length - 1);
        return new double[] {path[next][0], path[next][1]};
    }

    private static double toMetersX(double deltaLngDegrees, double cosLat) {
        return Math.toRadians(deltaLngDegrees) * EARTH_RADIUS_M * cosLat;
    }

    private static double toMetersY(double deltaLatDegrees) {
        return Math.toRadians(deltaLatDegrees) * EARTH_RADIUS_M;
    }
}
