package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;

public class Metrics {

    /*
     * Multiplicative complexity model:
     *  Cost = (instructions / 1e6) * (1 + 0.05·methods + 0.05·constructors) * (1 + log(1 + fragmentation))
     */

    private static final double BASE_DIVISOR = 1_000_000.0;

    private static final Pattern INSTRUCTIONS_PATTERN =
            Pattern.compile("instructions=(\\d+)");
    private static final Pattern BLOCKS_PATTERN =
            Pattern.compile("blocks=(\\d+)");
    private static final Pattern METHODS_PATTERN =
            Pattern.compile("methods=(\\d+)");
    private static final Pattern CONSTRUCTORS_PATTERN =
            Pattern.compile("constructors=(\\d+)");
    private static final Pattern FRAGMENTATION_PATTERN =
            Pattern.compile("fragmentation=([0-9]+(?:\\.[0-9]+)?)");

    private static final String TABLE = System.getenv("DYNAMODB_TABLE");

    private static final BlockingQueue<Map<String, AttributeValue>> BUFFER =
            new LinkedBlockingQueue<>();

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dynamo-writer");
        t.setDaemon(true);
        return t;
    });

    private static final AmazonDynamoDB DYNAMO = initDynamo();

    private static final ScheduledExecutorService FLUSHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dynamo-flusher");
        t.setDaemon(false);
        return t;
    });

    private Metrics() {}

    private static AmazonDynamoDB initDynamo() {
        if (TABLE == null) {
            System.err.println("[Metrics] DYNAMODB_TABLE not set — DynamoDB disabled");
            return null;
        }
        try {
            return AmazonDynamoDBClientBuilder.standard()
                    .withCredentials(new EnvironmentVariableCredentialsProvider())
                    .build();
        } catch (Exception e) {
            System.err.println("[Metrics] DynamoDB init failed: " + e.getMessage());
            return null;
        }
    }

    static {
        if (TABLE != null && DYNAMO != null) {
            FLUSHER.scheduleAtFixedRate(
                    Metrics::flushToDynamo,
                    0,
                    20,
                    TimeUnit.SECONDS
            );

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                FLUSHER.shutdown();
                try {
                    if (!FLUSHER.awaitTermination(5, TimeUnit.SECONDS)) {
                        // allow one last synchronous flush
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                flushToDynamo();
            }, "metrics-shutdown-flusher"));
        }
    }

    private static long extractLong(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private static double extractDouble(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    public static long computeComplexity(long instructions,
                                         long blocks,
                                         long methods,
                                         long constructors,
                                         double fragmentation) {

        double base = instructions / BASE_DIVISOR;

        double structural =
                1.0
                // + 0.05 * methods
                + 0.05 * constructors;

        double fragPenalty =
                1.0 + Math.log(1.0 + Math.max(0.0, fragmentation));

        double cost = base * structural * fragPenalty;

        return Math.max(1L, Math.round(cost));
    }

    public static synchronized void logMetric(String line) {

        long instructions  = extractLong(INSTRUCTIONS_PATTERN, line);
        long blocks        = extractLong(BLOCKS_PATTERN, line);
        long methods       = extractLong(METHODS_PATTERN, line);
        long constructors  = extractLong(CONSTRUCTORS_PATTERN, line);
        double fragmentation = extractDouble(FRAGMENTATION_PATTERN, line);

        long complexity = computeComplexity(
                instructions,
                blocks,
                methods,
                constructors,
                fragmentation
        );

        String outputLine = line + ",complexity=" + complexity;

        try (FileWriter writer = new FileWriter("metrics.log", true)) {
            writer.write(outputLine + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (TABLE != null && DYNAMO != null) {
            String[] parts = outputLine.split(",", 8);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("requestId", new AttributeValue(UUID.randomUUID().toString()));
            item.put("timestamp", new AttributeValue(parts.length > 0 ? parts[0] : ""));
            item.put("workload", new AttributeValue(parts.length > 1 ? parts[1] : ""));
            item.put("params", new AttributeValue(parts.length > 2 ? parts[2] : ""));

            item.put("instructions", new AttributeValue().withN(Long.toString(instructions)));
            item.put("blocks", new AttributeValue().withN(Long.toString(blocks)));
            item.put("methods", new AttributeValue().withN(Long.toString(methods)));
            item.put("constructors", new AttributeValue().withN(Long.toString(constructors)));
            item.put("fragmentation", new AttributeValue().withN(Double.toString(fragmentation)));
            item.put("complexity", new AttributeValue().withN(Long.toString(complexity)));


            String workload = parts.length > 1 ? parts[1] : "";
            String paramsBlob = parts.length > 2 ? parts[2] : "";
            Map<String, String> rawParams = parseParamsBlob(paramsBlob);

            String bucketKey = buildBucketKey(workload, rawParams);
            if (bucketKey != null) {
                item.put("bucketKey", new AttributeValue(bucketKey));
                item.put("workloadBucketKey", new AttributeValue(workload + "|" + bucketKey));
            }
            item.put("tsEpochMs", new AttributeValue().withN(Long.toString(System.currentTimeMillis())));


            boolean queued = BUFFER.offer(item);
            if (!queued) {
                WRITER.submit(() -> {
                    try {
                        DYNAMO.putItem(TABLE, item);
                    } catch (Exception e) {
                        System.err.println("[Metrics] DynamoDB fallback write failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    private static void flushToDynamo() {
        if (TABLE == null || DYNAMO == null) return;

        List<Map<String, AttributeValue>> drained = new ArrayList<>();
        BUFFER.drainTo(drained, 25);
        if (drained.isEmpty()) return;

        List<WriteRequest> batch = new ArrayList<>(drained.size());
        for (Map<String, AttributeValue> item : drained) {
            batch.add(new WriteRequest(new PutRequest().withItem(item)));
        }

        Map<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(TABLE, batch);

        BatchWriteItemRequest request = new BatchWriteItemRequest().withRequestItems(requestItems);

        int attempts = 0;
        while (true) {
            try {
                BatchWriteItemResult result = DYNAMO.batchWriteItem(request);
                Map<String, List<WriteRequest>> unprocessed = result.getUnprocessedItems();
                if (unprocessed == null || unprocessed.isEmpty()) break;
                attempts++;
                if (attempts > 5) {
                    System.err.println("[Metrics] Some items unprocessed after retries: " + unprocessed.size());
                    break;
                }
                request = new BatchWriteItemRequest().withRequestItems(unprocessed);
                try {
                    Thread.sleep(100L * (1 << attempts));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (Exception e) {
                System.err.println("[Metrics] DynamoDB batchWrite failed: " + e.getMessage());
                e.printStackTrace();
                attempts++;
                if (attempts > 5) break;
                try {
                    Thread.sleep(200L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static Integer parseIntOrNull(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseParamsBlob(String paramsBlob) {
        Map<String, String> map = new HashMap<>();
        if (paramsBlob == null || paramsBlob.isEmpty()) return map;

        String[] parts = paramsBlob.split(";");
        for (String p : parts) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            }
        }
        return map;
    }

    private static Integer deriveResolution(Map<String, String> raw) {
        Integer resolution = parseIntOrNull(raw.get("resolution"));
        if (resolution != null) return resolution;
        Integer w = parseIntOrNull(raw.get("w"));
        Integer h = parseIntOrNull(raw.get("h"));
        if (w == null || h == null) return null;
        long r = (long) w * (long) h;
        return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) r;
    }

    private static Integer deriveSeqLength(Map<String, String> raw) {
        Integer seqLength = parseIntOrNull(raw.get("seqLength"));
        if (seqLength != null) return seqLength;

        Integer seq1Len = parseIntOrNull(raw.get("seq1Length"));
        Integer seq2Len = parseIntOrNull(raw.get("seq2Length"));
        if (seq1Len == null || seq2Len == null) return null;

        long sum = (long) seq1Len + (long) seq2Len;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static String buildBucketKey(String workload, Map<String, String> rawParams) {
        if ("fractals".equals(workload)) {
            Integer iterations = parseIntOrNull(rawParams.get("iterations"));
            Integer resolution = deriveResolution(rawParams);
            if (iterations == null || resolution == null) return null;
            int bIterations = Math.floorDiv(iterations, 10) * 10;
            int bResolution = Math.floorDiv(resolution, 10) * 10;
            return "iterations=" + bIterations + "|resolution=" + bResolution;
        }

        if ("dna".equals(workload)) {
            Integer seqLength = deriveSeqLength(rawParams);
            if (seqLength == null) return null;
            int bSeq = Math.floorDiv(seqLength, 10) * 10;
            return "seqLength=" + bSeq;
        }

        if ("grayscott".equals(workload)) {
            Integer size = parseIntOrNull(rawParams.get("size"));
            Integer maxIterations = parseIntOrNull(rawParams.get("maxIterations"));
            if (size == null || maxIterations == null) return null;
            int bSize = Math.floorDiv(size, 10) * 10;
            int bIter = Math.floorDiv(maxIterations, 10) * 10;
            return "size=" + bSize + "|maxIterations=" + bIter;
        }

        return null;
    }
}