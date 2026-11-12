package graphroute.routing;

import graphroute.util.DistanceUtil;
import java.util.*;

/** Balanced 3-D kd-tree on unit-sphere coordinates; exact great-circle nearest-neighbour ordering. */
public final class SpatialIndex {
    public record Match(RoutingService.Place place, double distanceMeters, int pointsExamined) { }
    private record Point(RoutingService.Place place, double[] coordinates) { }
    private record Branch(Point point, int axis, Branch left, Branch right) { }
    private final Branch root;

    public SpatialIndex(List<RoutingService.Place> places) {
        List<Point> points = places.stream().map(p -> new Point(p, xyz(p.latitude(), p.longitude()))).toList();
        root = build(new ArrayList<>(points), 0);
    }

    public Match nearest(double latitude, double longitude) {
        if (root == null) throw new IllegalStateException("Cannot search an empty spatial index");
        double[] query = xyz(latitude, longitude);
        Best best = new Best();
        visit(root, query, best);
        var place = best.point.place();
        return new Match(place, DistanceUtil.haversine(latitude, longitude, place.latitude(), place.longitude()) * 1000, best.examined);
    }

    private static final class Best {
        Point point;
        double squared = Double.POSITIVE_INFINITY;
        int examined;
    }
    private static Branch build(List<Point> points, int depth) {
        if (points.isEmpty()) return null;
        int axis = depth % 3;
        points.sort(Comparator.comparingDouble((Point p) -> p.coordinates()[axis]).thenComparing(p -> p.place().id()));
        int middle = points.size() / 2;
        return new Branch(points.get(middle), axis,
                build(new ArrayList<>(points.subList(0, middle)), depth + 1),
                build(new ArrayList<>(points.subList(middle + 1, points.size())), depth + 1));
    }
    private static void visit(Branch branch, double[] query, Best best) {
        if (branch == null) return;
        best.examined++;
        double squared = 0;
        for (int axis = 0; axis < 3; axis++) {
            double delta = query[axis] - branch.point().coordinates()[axis];
            squared += delta * delta;
        }
        if (squared < best.squared || (squared == best.squared
                && branch.point().place().id().compareTo(best.point.place().id()) < 0)) {
            best.point = branch.point();
            best.squared = squared;
        }
        double delta = query[branch.axis()] - branch.point().coordinates()[branch.axis()];
        visit(delta <= 0 ? branch.left() : branch.right(), query, best);
        if (delta * delta <= best.squared) visit(delta <= 0 ? branch.right() : branch.left(), query, best);
    }
    private static double[] xyz(double latitude, double longitude) {
        double lat = Math.toRadians(latitude), lng = Math.toRadians(longitude);
        return new double[]{Math.cos(lat) * Math.cos(lng), Math.cos(lat) * Math.sin(lng), Math.sin(lat)};
    }
}
