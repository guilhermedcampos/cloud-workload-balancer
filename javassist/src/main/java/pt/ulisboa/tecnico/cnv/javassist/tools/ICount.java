package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class ICount extends CodeDumper {

    /**
     * Thread-local storage for instruction count, basic block count, and method count.
     */
    private static final ThreadLocal<Long> instructionCounter = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> blockCounter = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> methodCounter = ThreadLocal.withInitial(() -> 0L);

    public ICount(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    /**
     * Increment the basic block counter and instruction counter for the current thread.
     */
    public static void incBasicBlock(int position, int length) {
        blockCounter.set(blockCounter.get() + 1);
        instructionCounter.set(instructionCounter.get() + length);
    }

    /**
     * Increment the method counter for the current thread.
     */
    public static void incBehavior(String name) {
        methodCounter.set(methodCounter.get() + 1);
    }

    /**
     * Reset all counters for the current thread.
     */
    public static void reset() {
        instructionCounter.set(0L);
        blockCounter.set(0L);
        methodCounter.set(0L);
    }

    /**
     * Get the instruction counter value for the current thread.
     */
    public static long getInstructionCounter() {
        return instructionCounter.get();
    }

    /**
     * Get the basic block counter value for the current thread.
     */
    public static long getBlockCounter() {
        return blockCounter.get();
    }

    /**
     * Get the method counter value for the current thread.
     */
    public static long getMethodCounter() {
        return methodCounter.get();
    }

    /**
     * Remove all counters for the current thread.
     */
    public static void remove() {
        instructionCounter.remove();
        blockCounter.remove();
        methodCounter.remove();
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        behavior.insertAfter(String.format("%s.incBehavior(\"%s\");", ICount.class.getName(), behavior.getLongName()));

        if (behavior.getName().equals("main")) {
            behavior.insertAfter(String.format("%s.printStatistics();", ICount.class.getName()));
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", ICount.class.getName(), block.getPosition(), block.getLength()));
    }

    public static void printStatistics() {
        System.out.println(String.format("[%s] Instructions: %d, Blocks: %d, Methods: %d",
                ICount.class.getSimpleName(),
                getInstructionCounter(),
                getBlockCounter(),
                getMethodCounter()));
    }
}
