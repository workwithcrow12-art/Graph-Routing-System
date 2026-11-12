package graphroute.routing;

import graphroute.model.Edge;
import graphroute.model.Graph;
import java.text.Normalizer;
import java.util.*;

/** Immutable normalized text and trigram postings, built once per network. */
public final class PlaceSearchIndex {
    private record Entry(RoutingService.Place place, String name, String id, String text) { }
    public record SearchResult(List<RoutingService.Place> places, int candidatesExamined, int total, int offset) { }
    private final List<Entry> entries;
    private final Map<String, int[]> postings;

    public PlaceSearchIndex(Graph graph, List<RoutingService.Place> places) {
        Map<String, Set<String>> roads = new HashMap<>();
        for (Edge edge : graph.getAllEdges()) {
            String name = normalize(edge.getRoadName());
            roads.computeIfAbsent(edge.getSource().getId(), ignored -> new TreeSet<>()).add(name);
            roads.computeIfAbsent(edge.getDestination().getId(), ignored -> new TreeSet<>()).add(name);
        }
        List<Entry> documents = new ArrayList<>();
        Map<String, List<Integer>> mutablePostings = new HashMap<>();
        for (var place : places) {
            String name = normalize(place.name()), id = normalize(place.id());
            String text = name + " " + id + " " + String.join(" ", roads.getOrDefault(place.id(), Set.of()));
            int index = documents.size();
            documents.add(new Entry(place, name, id, text));
            for (String gram : grams(text)) mutablePostings.computeIfAbsent(gram, ignored -> new ArrayList<>()).add(index);
        }
        entries = List.copyOf(documents);
        Map<String, int[]> built = new HashMap<>();
        mutablePostings.forEach((gram, ids) -> built.put(gram, ids.stream().mapToInt(Integer::intValue).toArray()));
        postings = Map.copyOf(built);
    }

    public SearchResult search(String query, int limit) {
        return search(query, limit, 0, place -> true);
    }

    public SearchResult search(String query, int limit, int offset, java.util.function.Predicate<RoutingService.Place> filter) {
        if (limit < 1 || limit > 100) throw new RoutingException("INVALID_REQUEST", "limit must be between 1 and 100");
        if (offset < 0) throw new RoutingException("INVALID_REQUEST", "offset must be nonnegative");
        if (query == null || query.isBlank()) return new SearchResult(List.of(), 0, 0, offset);
        if (query.length() > 200) throw new RoutingException("INVALID_REQUEST", "Search queries must not exceed 200 characters");
        String term = normalize(query);
        if (term.isBlank()) return new SearchResult(List.of(), 0, 0, offset);
        int[] candidates = null;
        for (String gram : grams(term)) {
            int[] ids = postings.get(gram);
            if (ids == null) return new SearchResult(List.of(), 0, 0, offset);
            if (candidates == null || ids.length < candidates.length) candidates = ids;
        }
        List<Entry> matches = new ArrayList<>();
        if (candidates == null) {
            for (Entry entry : entries) if (entry.text().contains(term)) matches.add(entry);
        } else {
            for (int index : candidates) if (entries.get(index).text().contains(term)) matches.add(entries.get(index));
        }
        matches.removeIf(entry -> !filter.test(entry.place()));
        matches.sort(Comparator.comparingInt((Entry entry) -> rank(entry, term))
                .thenComparing(e -> e.place().name()).thenComparing(e -> e.place().id()));
        return new SearchResult(matches.stream().skip(offset).limit(limit).map(Entry::place).toList(),
                candidates == null ? entries.size() : candidates.length, matches.size(), offset);
    }

    private static int rank(Entry entry, String term) {
        if (entry.id().equals(term) || entry.name().equals(term)) return 0;
        return entry.name().startsWith(term) ? 1 : 2;
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().replaceAll("\\s+", " ");
    }
    private static Set<String> grams(String text) {
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 3 <= text.length(); index++) result.add(text.substring(index, index + 3));
        return result;
    }
}
