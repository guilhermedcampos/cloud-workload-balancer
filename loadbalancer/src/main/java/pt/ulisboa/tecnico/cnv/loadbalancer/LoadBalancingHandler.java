package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClient;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pt.ulisboa.tecnico.cnv.loadbalancer.metrics.CacheRefresher;
import pt.ulisboa.tecnico.cnv.loadbalancer.metrics.CostEstimator;
import pt.ulisboa.tecnico.cnv.loadbalancer.metrics.MetricsCache;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Supervisor;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Worker;

/**
 * Simple round-robin reverse proxy load balancer:
 * Receive req, Choose worker using round robin, Forward req to workers, Return resp to clients
 */
public class LoadBalancingHandler implements HttpHandler {

    private final String workloadType;
    private final List<String> paramNames;

    private final MetricsCache metricsCache;
    private final CacheRefresher cacheRefresher;
    private final CostEstimator costEstimator;

    private static final double HIGH_LOAD_CPU_THRESHOLD = 0.8;
    private static final int LAMBDA_MAX_COST = 50_000;
    private static final int EC2_PREFER_THRESHOLD = 20_000; // send lambda if cost is below this threshold and only high-load workers are available

    private final AWSLambda lambdaClient;


    public LoadBalancingHandler(String workloadType, MetricsCache metricsCache, CacheRefresher cacheRefresher, List<String> params, List<Integer> costs) {
        this.workloadType = workloadType;
        this.paramNames = List.copyOf(params);
        this.metricsCache = metricsCache;
        this.cacheRefresher = cacheRefresher;
        this.costEstimator = new CostEstimator(params, costs);
        this.lambdaClient = AWSLambdaClient.builder()
            .withCredentials(new EnvironmentVariableCredentialsProvider())
            .build();

        // Register this handler instance for its workload so re-dispatch uses the correct configuration.
        Supervisor.getInstance().setRehandleExchangeHandler(workloadType, (exchange, c) -> {
            new Thread(() -> {
                try {
                    // Try to rehandle the exchange by running through this handler again
                    this.handle(exchange);
                } catch (IOException e) {
                    System.err.println("Rehandle error: " + e.getMessage());
                }
            }).start();
        });
    }

    private Map<String, String> parseRawQuery(HttpExchange exchange) {
        Map<String, String> rawParams = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();

        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawParams;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            String name = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = keyValue.length < 2
                    ? ""
                    : URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
            rawParams.put(name, value);
        }

        return rawParams;
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Integer> getRequestParams(HttpExchange exchange) {
        Map<String, String> raw = parseRawQuery(exchange);
        Map<String, Integer> requestParams = new HashMap<>();

        for (String name : paramNames) {
            Integer direct = parseIntOrNull(raw.get(name));
            if (direct != null) {
                requestParams.put(name, direct);
            }
        }

        if ("fractals".equals(workloadType) && paramNames.contains("resolution")
                && !requestParams.containsKey("resolution")) {
            Integer resolution = computeFractalsResolution(raw);
            if (resolution != null) {
                requestParams.put("resolution", resolution);
            }
        }

        if ("dna".equals(workloadType) && paramNames.contains("seqLength")
                && !requestParams.containsKey("seqLength")) {
            Integer seqLength = computeDnaSeqLength(raw);
            if (seqLength != null) {
                requestParams.put("seqLength", seqLength);
            }
        }

        return requestParams;
    }

    /**
     * Builds destination worker URL.
     */
    private URL buildWorkerURL(HttpExchange exchange, Worker worker) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        String workerAddress = "http://" + worker.getInstance().getPublicIpAddress()
                + ":" + LoadBalancer.WORKER_PORT + "/" + workloadType;

        if (query != null && !query.isEmpty()) {
            workerAddress += "?" + query;
        }

        return new URL(workerAddress);
    }

    /**
     * Forward request headers.
     */
    private void copyRequestHeaders(HttpExchange exchange,
                                    HttpURLConnection connection) {

        exchange.getRequestHeaders().forEach((name, values) -> {

            if (!"Host".equalsIgnoreCase(name)) {

                for (String value : values) {
                    connection.addRequestProperty(name, value);
                }
            }
        });
    }

    /**
     * Forward response headers.
     */
    private void copyResponseHeaders(HttpURLConnection connection,
                                     HttpExchange exchange) {

        connection.getHeaderFields().forEach((name, values) -> {

            if (name != null) {
                exchange.getResponseHeaders().put(name, values);
            }
        });
    }

    /**
     * Forward POST request body.
     */
    private void forwardRequestBody(HttpExchange exchange,
                                    HttpURLConnection connection)
            throws IOException {

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {

            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream();
                 InputStream is = exchange.getRequestBody()) {

                is.transferTo(os);
            }
        }
    }


    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long requestId = LoadBalancer.requestId.incrementAndGet();
        Map<String, Integer> requestParams = getRequestParams(exchange);
        String bucketKey = metricsCache.bucketKey(requestParams);
        System.out.println("[LB] Received " + workloadType + " request with params=" + requestParams + " bucketKey=" + bucketKey);

        if (cacheRefresher != null && bucketKey != null) {
            cacheRefresher.recordAccess(workloadType, bucketKey);
        }

        Integer cost = metricsCache.lookup(requestParams);

        if (cost == null) {
            System.out.println("[LB] Cache miss for " + workloadType + " with bucketKey=" + bucketKey + ", estimating cost...");
            cost = costEstimator.estimate(requestParams);
        }

        Supervisor supervisor = Supervisor.getInstance();

        if (cost <= EC2_PREFER_THRESHOLD
                && supervisor.hasOnlyHighLoadActiveWorkers(HIGH_LOAD_CPU_THRESHOLD)) {
            System.out.println("[LB] All workers are under high load, invoking Lambda for " + workloadType + " with cost=" + cost);
            invokeLambda(exchange, cost);
            return;
        }

        Worker worker = supervisor.getOptimalWorker(cost);

        if (worker == null) {
            if (cost <= LAMBDA_MAX_COST) {
                System.out.println("[LB] No workers available, invoking Lambda for " + workloadType + " with cost=" + cost);
                invokeLambda(exchange, cost);
                return;
            }
            String msg = "503 No workers available";
            exchange.sendResponseHeaders(503, msg.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        supervisor.registerRequestForWorker(worker, requestId, cost, exchange);
        long start = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URL workerURL = buildWorkerURL(exchange, worker);

            System.out.println(
                    "[LB] "
                            + exchange.getRequestMethod()
                            + " "
                            + exchange.getRequestURI()
                            + " -> "
                            + workerURL
                            + " | bucket="
                            + bucketKey
                            + " | cost="
                            + cost
            );

            connection = (HttpURLConnection) workerURL.openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(300000);

            copyRequestHeaders(exchange, connection);
            connection.addRequestProperty("X-Request-Id", Long.toString(requestId));
            connection.addRequestProperty("X-Request-Cost", Integer.toString(cost));
            forwardRequestBody(exchange, connection);

            int responseCode = connection.getResponseCode();

            InputStream responseStream = responseCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();

            if (responseStream == null) {
                responseStream = InputStream.nullInputStream();
            }

            copyResponseHeaders(connection, exchange);

            byte[] responseBody = responseStream.readAllBytes();
            exchange.sendResponseHeaders(responseCode, responseBody.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }

            long elapsed = System.currentTimeMillis() - start;
            System.out.println("[LB] Completed " + workloadType + " in " + elapsed + " ms");

        } catch (Exception e) {
            e.printStackTrace();
            String message = "Load Balancer Error: " + e.getMessage();
            exchange.sendResponseHeaders(500, message.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(message.getBytes());
            }
        } finally {
            supervisor.completeRequestForWorker(worker, requestId);
            if (connection != null) {
                connection.disconnect();
            }
            exchange.close();
        }
    }

    private void invokeLambda(HttpExchange exchange, int cost) throws IOException {

        String query = exchange.getRequestURI().getRawQuery();

        String payload = "{"
                + "\"workload\":\"" + workloadType + "\","
                + "\"params\":\"" + (query == null ? "" : query.replace("\"", "\\\"")) + "\","
                + "\"cost\":" + cost
                + "}";

        InvokeRequest request = new InvokeRequest()
                .withFunctionName(System.getenv("LAMBDA_FUNCTION_NAME"))
                .withPayload(payload);

        InvokeResult response = lambdaClient.invoke(request);

        String resultString = new String(response.getPayload().array(), java.nio.charset.StandardCharsets.UTF_8);

        byte[] resultBytes = resultString.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try {
            exchange.sendResponseHeaders(200, resultBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resultBytes);
            }
        } finally {
            exchange.close();
        }
    }
    
    private Integer computeFractalsResolution(Map<String, String> rawParams) {
        Integer w = parseIntOrNull(rawParams.get("w"));
        Integer h = parseIntOrNull(rawParams.get("h"));
        if (w == null || h == null) {
            return null;
        }
        long res = (long) w * (long) h;
        if (res > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) res;
    }

    private int sequenceLength(String seqValue) {
        if (seqValue == null) {
            return 0;
        }
        int colon = seqValue.indexOf(':');
        String sequence = colon >= 0 ? seqValue.substring(colon + 1) : seqValue;
        return sequence.replaceAll("\\s+", "").length();
    }

    private Integer computeDnaSeqLength(Map<String, String> rawParams) {
        Integer explicit = parseIntOrNull(rawParams.get("seqLength"));
        if (explicit != null && explicit >= 0) {
            return explicit;
        }

        String seq1 = rawParams.get("seq1");
        String seq2 = rawParams.get("seq2");
        if (seq1 == null || seq2 == null) {
            return null;
        }

        long sum = (long) sequenceLength(seq1) + (long) sequenceLength(seq2);
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) sum;
    }
}