package org.schabi.newpipe.extractor;

import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Compat {
    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        if (elements == null || elements.length == 0) return Collections.emptyList();
        if (elements.length == 1) return Collections.singletonList(elements[0]);
        return Collections.unmodifiableList(Arrays.asList(elements));
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        if (elements == null || elements.length == 0) return Collections.emptySet();
        if (elements.length == 1) return Collections.singleton(elements[0]);
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(elements)));
    }

    public static <K, V> Map<K, V> mapOf() { return Collections.emptyMap(); }
    public static <K, V> Map<K, V> mapOf(K k1, V v1) { return Collections.singletonMap(k1, v1); }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2) {
        Map<K, V> m = new HashMap<>(); m.put(k1, v1); m.put(k2, v2); return Collections.unmodifiableMap(m);
    }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> m = new HashMap<>(); m.put(k1, v1); m.put(k2, v2); m.put(k3, v3); return Collections.unmodifiableMap(m);
    }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
        Map<K, V> m = new HashMap<>(); m.put(k1, v1); m.put(k2, v2); m.put(k3, v3); m.put(k4, v4); return Collections.unmodifiableMap(m);
    }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
        Map<K, V> m = new HashMap<>(); m.put(k1, v1); m.put(k2, v2); m.put(k3, v3); m.put(k4, v4); m.put(k5, v5); return Collections.unmodifiableMap(m);
    }

    public static <K, V> Map<K, V> mapOfEntries(Map.Entry<K, V>... entries) {
        Map<K, V> m = new HashMap<>();
        for (Map.Entry<K, V> e : entries) m.put(e.getKey(), e.getValue());
        return Collections.unmodifiableMap(m);
    }

    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }
}
