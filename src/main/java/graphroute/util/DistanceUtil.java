package graphroute.util;

public class DistanceUtil {
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * @param lat1 Latitude
     * @param lon1 Longitude
     * @param lat2 Latitude
     * @param lon2 Longitude
     * @return Distance in Kilometer
     */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        // Protect antipodal calculations from floating-point values slightly outside [0, 1].
        a = Math.max(0, Math.min(1, a));
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }
}
