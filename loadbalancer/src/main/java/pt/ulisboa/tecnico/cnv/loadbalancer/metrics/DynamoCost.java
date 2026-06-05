package pt.ulisboa.tecnico.cnv.loadbalancer.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;

public class DynamoCost {

    private final AmazonDynamoDB dynamo;
    private final String fractalsTable;
    private final String dnaTable;
    private final String grayscottTable;
    private final String indexName;
    private final int limit;

    public DynamoCost() {
        this.fractalsTable = System.getenv("DYNAMODB_TABLE_FRACTALS");
        this.dnaTable = System.getenv("DYNAMODB_TABLE_DNA");
        this.grayscottTable = System.getenv("DYNAMODB_TABLE_GRAYSCOTT");
        this.indexName = System.getenv().getOrDefault(
                "DYNAMODB_BUCKET_INDEX",
                "bucketKey-tsEpochMs-index"
        );
        this.limit = parsePositiveInt(System.getenv("DYNAMODB_QUERY_LIMIT"), 20);

        if (fractalsTable == null && dnaTable == null && grayscottTable == null) {
            this.dynamo = null;
            return;
        }

        this.dynamo = AmazonDynamoDBClientBuilder.standard()
                .withCredentials(new EnvironmentVariableCredentialsProvider())
                .build();
    }

    private String tableForWorkload(String workload) {
        if ("fractals".equals(workload)) return fractalsTable;
        if ("dna".equals(workload)) return dnaTable;
        if ("grayscott".equals(workload)) return grayscottTable;
        return null;
    }

    public Integer lookupCost(String workload, String bucketKey) {
        if (dynamo == null || workload == null || workload.isBlank() || bucketKey == null || bucketKey.isBlank()) {
            return null;
        }

        String table = tableForWorkload(workload);
        if (table == null || table.isBlank()) {
            return null;
        }

        try {
            HashMap<String, AttributeValue> values = new HashMap<>();
            values.put(":bk", new AttributeValue(bucketKey));

            QueryRequest request = new QueryRequest()
                    .withTableName(table)
                    .withIndexName(indexName)
                    .withKeyConditionExpression("bucketKey = :bk")
                    .withExpressionAttributeValues(values)
                    .withProjectionExpression("complexity")
                    .withScanIndexForward(false)
                    .withLimit(limit);

            QueryResult result = dynamo.query(request);
            if (result.getItems() == null || result.getItems().isEmpty()) {
                return null;
            }

            List<Integer> complexities = new ArrayList<>();
            result.getItems().forEach(item -> {
                AttributeValue c = item.get("complexity");
                if (c != null && c.getN() != null) {
                    try {
                        complexities.add((int) Math.min(Integer.MAX_VALUE, Long.parseLong(c.getN())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });

            if (complexities.isEmpty()) {
                return null;
            }

            return median(complexities);
        } catch (Exception e) {
            System.out.println("[DynamoCost] query failed for key " + bucketKey + ": " + e.getMessage());
            return null;
        }
    }

    private int median(List<Integer> values) {
        Collections.sort(values);
        int n = values.size();
        if (n % 2 == 1) {
            return values.get(n / 2);
        }
        long a = values.get((n / 2) - 1);
        long b = values.get(n / 2);
        return (int) ((a + b) / 2L);
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}