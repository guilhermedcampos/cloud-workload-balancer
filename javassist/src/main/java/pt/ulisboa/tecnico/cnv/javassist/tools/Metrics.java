package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Metrics {

    /*
     * Complexity model:
     * - Instructions (70%): best approximation of total CPU work.
     * - Basic blocks (20%): captures control-flow complexity.   
     * - Methods (10%): captures call overhead.
    */

    // TODO - Consider basic block size, constructor invocations
    // Divide complexity by some factor

    private static final double INSTRUCTION_WEIGHT = 0.7;
    private static final double BLOCK_WEIGHT = 0.2;
    private static final double METHOD_WEIGHT = 0.1;

    private static final Pattern INSTRUCTIONS_PATTERN =
            Pattern.compile("instructions=(\\d+)");

    private static final Pattern BLOCKS_PATTERN =
            Pattern.compile("blocks=(\\d+)");

    private static final Pattern METHODS_PATTERN =
            Pattern.compile("methods=(\\d+)");

    private Metrics() {
    }

    private static long extractMetric(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0L;
    }

    public static long computeComplexity(long instructions,
                                         long blocks,
                                         long methods) {
        double complexity =
                INSTRUCTION_WEIGHT * instructions +
                BLOCK_WEIGHT * blocks +
                METHOD_WEIGHT * methods;

        return Math.round(complexity);
    }

    public static synchronized void logMetric(String line) {
        long instructions = extractMetric(INSTRUCTIONS_PATTERN, line);
        long blocks = extractMetric(BLOCKS_PATTERN, line);
        long methods = extractMetric(METHODS_PATTERN, line);

        long complexity = computeComplexity(instructions, blocks, methods);

        String complexityLine = line + ",complexity=" + complexity;

        try (FileWriter writer = new FileWriter("metrics.log", true)) {
            writer.write(complexityLine + System.lineSeparator());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}