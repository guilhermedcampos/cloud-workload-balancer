package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Supervisor;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Worker;

/**
 * Simple round-robin reverse proxy load balancer:
 * Receive req, Choose worker using round robin, Forward req to workers, Return resp to clients
 */
public class LoadBalancingHandler implements HttpHandler {

    /** Shared round-robin counter across all handlers. */
    private static final AtomicInteger workerIndex = new AtomicInteger(0);

    /**
     * Worker pool.
     */
    private static final List<String> workers = List.of(
        "localhost",
        "localhost"
        // xxx.compute-1.amazonaws.com",
        // xxx.compute-1.amazonaws.com"
    );

    private final String workloadType;

    public LoadBalancingHandler(String workloadType) {
        this.workloadType = workloadType;
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
        int cost = 100; // placeholder until you add real estimation

        Worker worker = Supervisor.getInstance().getBestWorker(cost);
        if (worker == null) {
            throw new RuntimeException("No workers available");
        }
        Supervisor.getInstance().registerRequestForWorker(worker, requestId, cost);

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
            );

            connection = (HttpURLConnection) workerURL.openConnection();

            connection.setRequestMethod(exchange.getRequestMethod());

            connection.setConnectTimeout(5000);

            // Workloads can take long.
            connection.setReadTimeout(300000);

            // Forward request data.
            copyRequestHeaders(exchange, connection);
            connection.addRequestProperty("X-Request-Id", Long.toString(requestId));
            forwardRequestBody(exchange, connection);

            // Obtain worker response.
            int responseCode = connection.getResponseCode();

            InputStream responseStream =
                    responseCode >= 400
                            ? connection.getErrorStream()
                            : connection.getInputStream();

            if (responseStream == null) {
                responseStream = InputStream.nullInputStream();
            }

            // Forward response headers.
            copyResponseHeaders(connection, exchange);

            // Read worker response.
            byte[] responseBody = responseStream.readAllBytes();

            // Send response to client.
            exchange.sendResponseHeaders(responseCode, responseBody.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }

            long elapsed = System.currentTimeMillis() - start;

            System.out.println(
                    "[LB] Completed "
                    + workloadType
                    + " in "
                    + elapsed
                    + " ms"
            );

        } catch (Exception e) {

            e.printStackTrace();

            String message = "Load Balancer Error: " + e.getMessage();

            exchange.sendResponseHeaders(500, message.length());

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(message.getBytes());
            }

        } finally {

            if (connection != null) {
                connection.disconnect();
            }

            exchange.close();
        }
    }
}