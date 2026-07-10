package org.nebula.entity;

/** Executable fluid task logic using only the traced {@link FluidContext}. */
@FunctionalInterface
public interface FluidAction {
    void execute(FluidContext ctx) throws Exception;
}
