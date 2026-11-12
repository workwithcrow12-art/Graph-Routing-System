package graphroute.routing;

import java.time.Instant;
import java.time.Duration;
import java.util.*;

/** Input contract for an observed external traffic feed; no fabricated live values. */
public record TrafficSnapshot(String source, String observedAt, String expiresAt, List<Update> updates) {
    public static final int MAX_UPDATES = 10_000;
    public record Update(String from, String to, String sourceWayId, String mode, Double speedKph,
                         boolean closed, String segmentId) {
        /** Legacy selectors still apply to every matching parallel arc. */
        public Update(String from, String to, String sourceWayId, String mode, Double speedKph, boolean closed) {
            this(from, to, sourceWayId, mode, speedKph, closed, null);
        }
    }
    private record Selector(String from, String to, String way, TravelMode mode, String segmentId) { }

    public TrafficSnapshot validated(Instant now) {
        if (source == null || source.isBlank() || source.length() > 200) throw invalid("Traffic source must be provided (at most 200 characters)");
        Instant observed, expires;
        try { observed = Instant.parse(observedAt); expires = Instant.parse(expiresAt); }
        catch (RuntimeException exception) { throw invalid("Traffic timestamps must be ISO-8601 UTC instants"); }
        if (observed.isAfter(now.plusSeconds(300)) || !expires.isAfter(now) || !expires.isAfter(observed)
                || Duration.between(observed, expires).compareTo(Duration.ofHours(24)) > 0) {
            throw invalid("Traffic must be current, expire after observation, and have a lifetime at most 24 hours");
        }
        if (updates == null || updates.size() > MAX_UPDATES) throw invalid("Traffic updates must be an array of at most 10000 entries");
        Map<Selector, Integer> keys = new HashMap<>();
        for (int index = 0; index < updates.size(); index++) {
            Update update = updates.get(index);
            if (update == null || update.from() == null || update.from().isBlank()
                    || update.to() == null || update.to().isBlank()) throw invalid("Traffic updates[" + index + "] requires nonblank from/to node IDs");
            if (update.segmentId() != null && update.segmentId().isBlank()) throw invalid("Traffic updates[" + index + "].segmentId must not be blank");
            TravelMode mode = TravelMode.parse(update.mode());
            if (update.speedKph() != null && (!Double.isFinite(update.speedKph()) || update.speedKph() <= 0 || update.speedKph() > 200)) {
                throw invalid("Traffic speedKph must be greater than 0 and at most 200");
            }
            if (!update.closed() && update.speedKph() == null) throw invalid("Traffic update requires speedKph or closed=true");
            Selector key = update.segmentId() == null
                    ? new Selector(update.from(), update.to(), update.sourceWayId(), mode, null)
                    : new Selector(null, null, null, mode, update.segmentId());
            Integer previous = keys.putIfAbsent(key, index);
            if (previous != null) throw invalid("Duplicate traffic updates[" + previous + "] and updates[" + index
                    + "] for " + describe(update) + "; use distinct segmentId values from /api/map for individual arcs");
        }
        return new TrafficSnapshot(source, observedAt, expiresAt, List.copyOf(updates));
    }

    public boolean expired(Instant now) { return !Instant.parse(expiresAt).isAfter(now); }

    static String describe(Update update) {
        return update.from() + " -> " + update.to() + ", sourceWayId="
                + Objects.toString(update.sourceWayId(), "<all>") + ", mode=" + update.mode()
                + ", segmentId=" + Objects.toString(update.segmentId(), "<all matching arcs>");
    }
    private static RoutingException invalid(String message) { return new RoutingException("INVALID_REQUEST", message); }
}
