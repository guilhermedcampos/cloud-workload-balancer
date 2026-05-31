package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class MetricsCache {
    private final List<String> paramNames;
    private final List<Integer> bucketCounts;
    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();


    /**
     * Create a MetricsCache with a bucket count per parameter.
     *
     * @param paramNames ordered list of parameter names that will be present in lookup/cache maps
     * @param bucketCounts ordered list of bucket counts matching the paramNames list; each value must be > 0
     */
    public MetricsCache(List<String> paramNames, List<Integer> bucketCounts) {
        if (paramNames == null || paramNames.isEmpty()) {
            throw new IllegalArgumentException("paramNames must not be null or empty");
        }
        if (bucketCounts == null || bucketCounts.size() != paramNames.size()) {
            throw new IllegalArgumentException("bucketCounts must be non-null and match paramNames size");
        }
        for (Integer b : bucketCounts) {
            if (b == null || b <= 0) throw new IllegalArgumentException("each bucket count must be > 0");
        }
        this.paramNames = Collections.unmodifiableList(new ArrayList<>(paramNames));
        this.bucketCounts = Collections.unmodifiableList(new ArrayList<>(bucketCounts));
    }

    private String keyFor(Map<String, Integer> params) {
        StringBuilder sb = new StringBuilder(paramNames.size() * 8);
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            Integer value = params.get(name);
            if (value == null) {
                return null; // missing expected parameter
            }
            int bucketSize = bucketCounts.get(i);
            int index = Math.floorDiv(value, bucketSize);
            int bucketStart = index * bucketSize;
            if (sb.length() > 0) sb.append('|');
            sb.append(name).append('=').append(bucketStart);
        }
        return sb.toString();
    }

    /**
     * Lookup a cached cost for the provided parameters.
     * Returns {@code null} if there is no cached value or if required parameters are missing.
     */
    public Integer lookup(Map<String, Integer> params) {
        String key = keyFor(params);
        if (key == null) return null;
        return cache.get(key);
    }

    public void cache(Map<String, Integer> params, int cost) {
        String key = keyFor(params);
        if (key == null) throw new IllegalArgumentException("missing required parameter(s)");
        cache.put(key, cost);
    }

    public void clear() { cache.clear(); }

    public int size() { return cache.size(); }
}