package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

public class Metrics {

    /*
     * Complexity model:
     * - Instructions (70%): best approximation of total CPU work.
     * - Basic blocks (20%): captures control-flow complexity.
     * - Methods (10%): captures call overhead.
    */

    private static final double INSTRUCTION_WEIGHT = 0.7;
    private static final double BLOCK_WEIGHT = 0.2;
    private static final double METHOD_WEIGHT = 0.1;

    private static final Pattern INSTRUCTIONS_PATTERN = Pattern.compile("instructions=(\\d+)");
    private static final Pattern BLOCKS_PATTERN       = Pattern.compile("blocks=(\\d+)");
    private static final Pattern METHODS_PATTERN      = Pattern.compile("methods=(\\d+)");

    private static final String TABLE = System.getenv("DYNAMODB_TABLE");

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dynamo-writer");
        t.setDaemon(true);
        return t;
    });

    private static final AmazonDynamoDB DYNAMO = initDynamo();

    private Metrics() {}

    private static AmazonDynamoDB initDynamo() {
        if (TABLE == null) {
            System.err.println("[Metrics] DYNAMODB_TABLE not set — DynamoDB disabled");
            return null;
        }
        try {
            AmazonDynamoDB client = AmazonDynamoDBClientBuilder.standard()
                    .withCredentials(new EnvironmentVariableCredentialsProvider())
                    .build();
            System.err.println("[Metrics] DynamoDB client initialized for table: " + TABLE);
            return client;
        } catch (Exception e) {
            System.err.println("[Metrics] DynamoDB init failed: " + e.getMessage());
            return null;
        }
    }

    private static long extractMetric(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    public static long computeComplexity(long instructions, long blocks, long methods) {
        return Math.round(INSTRUCTION_WEIGHT * instructions + BLOCK_WEIGHT * blocks + METHOD_WEIGHT * methods);
    }

    public static synchronized void logMetric(String line) {
        long instructions = extractMetric(INSTRUCTIONS_PATTERN, line);
        long blocks       = extractMetric(BLOCKS_PATTERN, line);
        long methods      = extractMetric(METHODS_PATTERN, line);
        long complexity   = computeComplexity(instructions, blocks, methods);

        String outputLine = line + ",complexity=" + complexity;

        try (FileWriter writer = new FileWriter("metrics.log", true)) {
            writer.write(outputLine + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (TABLE != null && DYNAMO != null) {
            final long fi = instructions, fb = blocks, fm = methods, fc = complexity;
            final String fl = outputLine;
            WRITER.submit(() -> {
                try {
                    // format: timestamp,workload,params,instructions=X,blocks=Y,methods=Z,threadId,complexity=N
                    String[] parts = fl.split(",", 8);
                    Map<String, AttributeValue> item = new HashMap<>();
                    item.put("requestId",    new AttributeValue(UUID.randomUUID().toString()));
                    item.put("timestamp",    new AttributeValue(parts.length > 0 ? parts[0] : ""));
                    item.put("workload",     new AttributeValue(parts.length > 1 ? parts[1] : ""));
                    item.put("params",       new AttributeValue(parts.length > 2 ? parts[2] : ""));
                    item.put("instructions", new AttributeValue().withN(Long.toString(fi)));
                    item.put("blocks",       new AttributeValue().withN(Long.toString(fb)));
                    item.put("methods",      new AttributeValue().withN(Long.toString(fm)));
                    item.put("complexity",   new AttributeValue().withN(Long.toString(fc)));
                    DYNAMO.putItem(TABLE, item);
                } catch (Exception e) {
                    System.err.println("[Metrics] DynamoDB write failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }
}
