package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class ICount extends CodeDumper {

    /**
     * Thread-local counters.
     */
    private static final ThreadLocal<Long> instructionCounter =
            ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> blockCounter =
            ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> methodCounter =
            ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> constructorCounter =
            ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> blockSizeSum =
            ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> blockSizeSquaredSum =
            ThreadLocal.withInitial(() -> 0L);

    public ICount(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    /**
     * Basic block instrumentation.
     */
    public static void incBasicBlock(int position, int length) {
        blockCounter.set(blockCounter.get() + 1);
        instructionCounter.set(instructionCounter.get() + length);

        blockSizeSum.set(blockSizeSum.get() + length);
        blockSizeSquaredSum.set(
                blockSizeSquaredSum.get() + (long) length * length
        );
    }

    /**
     * Method execution tracking.
     */
    public static void incBehavior(String name) {
        methodCounter.set(methodCounter.get() + 1);
    }

    /**
     * Constructor tracking.
     */
    public static void incConstructor() {
        constructorCounter.set(constructorCounter.get() + 1);
    }

    /**
     * Reset counters for a new request.
     */
    public static void reset() {
        instructionCounter.set(0L);
        blockCounter.set(0L);
        methodCounter.set(0L);
        constructorCounter.set(0L);
        blockSizeSum.set(0L);
        blockSizeSquaredSum.set(0L);
    }

    public static long getInstructionCounter() {
        return instructionCounter.get();
    }

    public static long getBlockCounter() {
        return blockCounter.get();
    }

    public static long getMethodCounter() {
        return methodCounter.get();
    }

    public static long getConstructorCounter() {
        return constructorCounter.get();
    }

    public static long getBlockSizeSum() {
        return blockSizeSum.get();
    }

    public static long getBlockSizeSquaredSum() {
        return blockSizeSquaredSum.get();
    }

    /**
     * Simple fragmentation estimate (variance-like metric).
     */
    public static double getBlockFragmentation() {
        long blocks = getBlockCounter();
        if (blocks == 0) return 0.0;

        double mean = (double) getBlockSizeSum() / blocks;
        double meanSq = (double) getBlockSizeSquaredSum() / blocks;

        return Math.max(0.0, meanSq - mean * mean);
    }

    /**
     * Remove all counters for the current thread.
     */
    public static void remove() {
        instructionCounter.remove();
        blockCounter.remove();
        methodCounter.remove();
        constructorCounter.remove();
        blockSizeSum.remove();
        blockSizeSquaredSum.remove();
    }


    /**
     * IMPORTANT: instrument behavior entry/exit.
     */
    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        boolean isConstructor = behavior.getName().equals("<init>");
        boolean isMain = behavior.getName().equals("main");

        if (isConstructor) {
            behavior.insertAfter(
                    ICount.class.getName() + ".incConstructor();"
            );
        } else {
            behavior.insertAfter(
                    ICount.class.getName() +
                    ".incBehavior(\"" + behavior.getLongName() + "\");"
            );
        }

        if (isMain) {
            behavior.insertAfter(
                    ICount.class.getName() + ".printStatistics();"
            );
        }
    }

    /**
     * Inject block instrumentation.
     */
    @Override
    protected void transform(BasicBlock block)
            throws CannotCompileException {

        super.transform(block);

        block.behavior.insertAt(
                block.line,
                String.format(
                        "%s.incBasicBlock(%s, %s);",
                        ICount.class.getName(),
                        block.getPosition(),
                        block.getLength()
                )
        );
    }

    public static void printStatistics() {
        System.out.println(
                String.format(
                        "[ICount] instructions=%d, blocks=%d, methods=%d, constructors=%d, fragmentation=%.4f",
                        getInstructionCounter(),
                        getBlockCounter(),
                        getMethodCounter(),
                        getConstructorCounter(),
                        getBlockFragmentation()
                )
        );
    }
}