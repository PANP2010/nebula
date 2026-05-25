package org.nebula.core.state;

public record RandomUsage(RandomInstance instance, int maxCallsEstimate) {
    public RandomUsage {
        if (maxCallsEstimate < 0) {
            throw new IllegalArgumentException("maxCallsEstimate must be non-negative");
        }
    }

    public static RandomUsage empty() {
        return new RandomUsage(RandomInstance.NONE, 0);
    }
}
