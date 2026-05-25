package org.nebula.core.scheduler;

@FunctionalInterface
public interface TaskAction {
    void execute() throws Exception;
}
