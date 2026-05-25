package org.nebula.guard;

import java.util.List;

public final class RWGuardViolationException extends RuntimeException {
    private final List<RWSetViolation> violations;

    public RWGuardViolationException(List<RWSetViolation> violations) {
        super("RW guard detected " + violations.size() + " violation(s)");
        this.violations = List.copyOf(violations);
    }

    public List<RWSetViolation> violations() {
        return violations;
    }
}
