package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;


public class LoadBalancer {
    public static boolean LOCALHOST = false;
    public static int LB_PORT = 8080;
    public static final int WORKER_PORT = 8000;

    public static void main(String[] args) throws Exception {

        if (args.length == 1) {
            if ("--local".equals(args[0])) {
                LoadBalancer.LOCALHOST = true;
                LoadBalancer.LB_PORT = 8080; 
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(LB_PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/test", exchange -> {
            String response = "Load Balancer OK";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });

        server.createContext("/fractals", new LoadBalancingHandler("fractals"));
        server.createContext("/dna", new LoadBalancingHandler("dna"));
        server.createContext("/grayscott", new LoadBalancingHandler("grayscott"));

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