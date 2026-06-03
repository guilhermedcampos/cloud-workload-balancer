package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class HealthHandler implements HttpHandler{
    @Override
    public void handle(HttpExchange he) throws IOException {
        double cpuLoad = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class).getSystemCpuLoad();
        byte[] body = ("OK: " + String.format("%.3f", cpuLoad)).getBytes();
        he.sendResponseHeaders(200, body.length);
        try (OutputStream os = he.getResponseBody()) {
            os.write(body);
        }
        he.close();
    }
}
