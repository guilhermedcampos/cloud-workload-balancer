package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.loadbalancer.autoscaler.AutoScaler;
import pt.ulisboa.tecnico.cnv.loadbalancer.supervisor.Supervisor;

public class LoadBalancer {
    public static final AtomicLong requestId = new AtomicLong(0);
    public static boolean LOCALHOST = false;
    public static int LB_PORT = 8080;
    public static final int WORKER_PORT = 8000;

    // Parameter names and bucket counts for MetricsCache (adjust as needed)
    public static final List<String> FRACTALS_PARAMS = List.of("iterations", "resolution");
    public static final List<Integer> FRACTALS_BUCKETS = List.of(10, 10);
    public static final List<Integer> FRACTALS_COSTS = List.of(1, 1);
    public static final List<String> DNA_PARAMS = List.of("seqLength");
    public static final List<Integer> DNA_BUCKETS = List.of(10);
    public static final List<Integer> DNA_COSTS = List.of(1);
    public static final List<String> GRAYSCOTT_PARAMS = List.of("size", "maxIterations");
    public static final List<Integer> GRAYSCOTT_BUCKETS = List.of(10, 10);
    public static final List<Integer> GRAYSCOTT_COSTS = List.of(1, 1);
    
    public static void main(String[] args) throws Exception {
        
        if (args.length == 1) {
            if ("--local".equals(args[0])) {
                LoadBalancer.LOCALHOST = true;
                LoadBalancer.LB_PORT = 8080; 
            }
        }
        Supervisor supervisor = Supervisor.getInstance();
        supervisor.start();
        
        AutoScaler autoScaler = AutoScaler.getInstance();
        autoScaler.start();

        HttpServer server = HttpServer.create(new InetSocketAddress(LB_PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/test", exchange -> {
            String response = "Load Balancer OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

        server.createContext("/fractals", new LoadBalancingHandler("fractals", FRACTALS_PARAMS, FRACTALS_BUCKETS, FRACTALS_COSTS));
        server.createContext("/dna", new LoadBalancingHandler("dna", DNA_PARAMS, DNA_BUCKETS, DNA_COSTS));
        server.createContext("/grayscott", new LoadBalancingHandler("grayscott", GRAYSCOTT_PARAMS, GRAYSCOTT_BUCKETS, GRAYSCOTT_COSTS));

        server.createContext("/", exchange -> {
            String response = "Endpoint not found";
            exchange.sendResponseHeaders(404, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

        System.out.println("Load Balancer started on port " + LB_PORT);


        server.start();
    }
}