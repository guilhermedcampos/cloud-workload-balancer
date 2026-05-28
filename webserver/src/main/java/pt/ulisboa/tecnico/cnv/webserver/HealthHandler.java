package pt.ulisboa.tecnico.cnv.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.sun.management.OperatingSystemMXBean;

public class HealthHandler implements HttpHandler{
    @Override
    public void handle(HttpExchange he) throws IOException {
        double cpuLoad = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class).getSystemCpuLoad();

        he.sendResponseHeaders(200, 0);
        OutputStream os = he.getResponseBody();
        os.write(("OK: " + String.format("%.3f", cpuLoad)).getBytes());
        os.close();
    }
}
