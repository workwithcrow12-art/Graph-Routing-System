package graphroute.routing;

import java.util.Locale;

/** Transport profiles; speeds and durations are derived from each road's mode data. */
public enum TravelMode {
    DRIVING, MOTORCYCLING, CYCLING, WALKING;

    public static TravelMode parse(String value) {
        if (value == null) return DRIVING;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new RoutingException("INVALID_REQUEST", "mode must be driving, motorcycling, cycling, or walking");
        }
    }
}
