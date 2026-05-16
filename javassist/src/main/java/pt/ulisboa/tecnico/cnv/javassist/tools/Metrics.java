package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.io.FileWriter;
import java.io.IOException;

public class Metrics {

    private Metrics() {
    }

    public static synchronized void logMetric(String line) {
        try (FileWriter writer = new FileWriter("metrics.log", true)) {
            writer.write(line + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}