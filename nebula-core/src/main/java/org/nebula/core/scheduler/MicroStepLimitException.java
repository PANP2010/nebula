package org.nebula.core.scheduler;

/**
 * Thrown when {@link MicroStepExtender} exceeds its microstep cap within a single tick.
 * Indicates either a runaway redstone circuit or a bug in the task generator.
 */
public final class MicroStepLimitException extends RuntimeException {

    private final int stepsExecuted;

    public MicroStepLimitException(int stepsExecuted, String message) {
        super(message);
        this.stepsExecuted = stepsExecuted;
    }

    public int stepsExecuted() {
        return stepsExecuted;
    }
}
