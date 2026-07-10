package org.nebula.entity;

/** Pure explosion action used to exercise declared RW footprints before live NMS wiring. */
@FunctionalInterface
public interface ExplosionAction {

    void execute(ExplosionContext context) throws Exception;
}
