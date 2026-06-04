package pt.ulisboa.tecnico.cnv.loadbalancer.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CacheRefresher {

    private static final long RECENT_WINDOW_MS = 5 * 60_000L;

    private final DynamoCost dynamo;
    private final MetricsCache fractalsCache;
    private final MetricsCache dnaCache;
    private final MetricsCache grayScottCache;
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    public CacheRefresher(
            DynamoCost dynamo,
            MetricsCache fractalsCache,
            MetricsCache dnaCache,
            MetricsCache grayScottCache) {

        this.dynamo = dynamo;
        this.fractalsCache = fractalsCache;
        this.dnaCache = dnaCache;
        this.grayScottCache = grayScottCache;
    }

    public void recordAccess(String workload, String bucketKey) {
        if (workload == null || bucketKey == null || bucketKey.isBlank()) {
            return;
        }
        lastSeen.put(workload + "|" + bucketKey, System.currentTimeMillis());
    }

    public void refresh() {
        refreshWorkload("fractals", fractalsCache);
        refreshWorkload("dna", dnaCache);
        refreshWorkload("grayscott", grayScottCache);
    }

    private void refreshWorkload(String workload, MetricsCache cache) {
        long now = System.currentTimeMillis();
        Set<String> keys = cache.snapshotKeys();

        for (String bucketKey : keys) {
            String seenKey = workload + "|" + bucketKey;
            Long last = lastSeen.get(seenKey);

            if (last == null) {
                continue;
            }

            if (now - last > RECENT_WINDOW_MS) {
                continue;
            }

            Integer cost = dynamo.lookupCost(workload, bucketKey);
            if (cost != null) {
                cache.cacheByBucketKey(bucketKey, cost);
            }
        }
    }
}