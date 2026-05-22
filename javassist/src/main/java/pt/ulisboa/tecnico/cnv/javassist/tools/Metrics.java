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
            return AmazonDynamoDBClientBuilder.standard()
                    .withCredentials(new EnvironmentVariableCredentialsProvider())
                    .build();
        } catch (Exception e) {
            System.err.println("[Metrics] DynamoDB init failed: " + e.getMessage());
            return null;
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
                + 0.05 * methods
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
            final long fi = instructions, fb = blocks, fm = methods, fc = constructors, fcomp = complexity;
            final String fl = outputLine;

            WRITER.submit(() -> {
                try {
                    String[] parts = fl.split(",", 8);

                    Map<String, AttributeValue> item = new HashMap<>();
                    item.put("requestId", new AttributeValue(UUID.randomUUID().toString()));
                    item.put("timestamp", new AttributeValue(parts.length > 0 ? parts[0] : ""));
                    item.put("workload", new AttributeValue(parts.length > 1 ? parts[1] : ""));
                    item.put("params", new AttributeValue(parts.length > 2 ? parts[2] : ""));

                    item.put("instructions", new AttributeValue().withN(Long.toString(fi)));
                    item.put("blocks", new AttributeValue().withN(Long.toString(fb)));
                    item.put("methods", new AttributeValue().withN(Long.toString(fm)));
                    item.put("constructors", new AttributeValue().withN(Long.toString(fc)));
                    item.put("complexity", new AttributeValue().withN(Long.toString(fcomp)));

                    DYNAMO.putItem(TABLE, item);

                } catch (Exception e) {
                    System.err.println("[Metrics] DynamoDB write failed: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }
}