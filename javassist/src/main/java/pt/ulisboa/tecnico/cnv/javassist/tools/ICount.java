package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class ICount extends CodeDumper {

    /**
     * Thread-local storage for instruction count.
     */
    private static final ThreadLocal<Long> counter = ThreadLocal.withInitial(() -> 0L);

    public ICount(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    /**
     * Increment the basic block counter for the current thread.
     */
    public static void incBasicBlock(int position, int length) {
        counter.set(counter.get() + length);
    }

    /**
     * Increment the method counter for the current thread.
     */
    public static void incBehavior(String name) {
        // No changes needed here for now.
    }

    /**
     * Reset the counter for the current thread.
     */
    public static void reset() {
        counter.set(0L);
    }

    /**
     * Get the counter value for the current thread.
     */
    public static long getCounter() {
        return counter.get();
    }

    /**
     * Remove the counter for the current thread.
     */
    public static void remove() {
        counter.remove();
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

}
