package org.nebula.folia;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.nebula.core.state.DimensionIds;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.TerrainView;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Live {@link TerrainView} backed by Folia block reads — the production terrain
 * oracle for the entity MOVE physics (B8 C1).
 *
 * <p><b>Why this exists (the dead-fixed-point bug, live-diagnosed 2026-07-10).</b>
 * {@link org.nebula.entity.actions.EntityMoveAction} resolves terrain collision and
 * its grounded fixed point ({@code restedOn}) through a {@link TerrainView}. Its unit
 * tests supply {@code flatFloor}/{@code ofSolids} views and pass — but the LIVE entity
 * runner was constructed with no terrain, so it ran against {@link TerrainView#EMPTY}
 * (open void). Every grounded, walking mob was therefore modelled as free-falling: the
 * divergence probe measured a rock-constant {@code meanSigned Δy = -0.0784}/tick (exactly
 * one tick of ungrounded gravity+drag, {@code (0 + -0.08) * 0.98}) for a mob resting on
 * the ground at an integer Y, while free-falling entities showed zero drift. Wiring a
 * real solidity oracle is what lets {@code restedOn} fire live so a grounded mob is a
 * clean {@code vel.y == 0} fixed point instead of perpetually over-falling.
 *
 * <p><b>Solidity model.</b> A cell is solid iff {@code Material.isSolid()} — the same
 * boolean notion of "an entity cannot occupy / can stand on this block" that the pure
 * {@code TerrainView} contract expresses. This is a first, honest approximation: it does
 * not model partial-height blocks (slabs, stairs, fences) whose true collision box is
 * finer than one cell. Those refine the LANDING Y and are the next drift source to chase;
 * the full-cube case (stone/dirt/grass — what an ordinary mob walks on) is exactly the
 * one the {@code -0.0784} finding was dominated by.
 *
 * <p><b>Thread-safety and the Folia region contract.</b> Stateless apart from a
 * dimension→world cache; one instance is shared across all region threads and set ONCE
 * on the runner (never swapped per tick), so there is no race — the runner's terrain
 * field is written at wire time and only read thereafter. {@link #isSolid} calls
 * {@code world.getBlockAt(...)}, which is only legal on the region thread that owns the
 * chunk; the entity DAG already runs {@code syncPhysicsFromNms} on that owning region
 * thread and MOVE reads only the column cells its RW-set declares, so the terrain read
 * rides the same legal region-thread access the slice already established.
 */
public final class NmsTerrainView implements TerrainView {

    private final Server server;
    // Solidity test for a block's material. Defaults to Material::isSolid live; injectable
    // so a unit test can exercise the dim-mapping/cell-resolution logic WITHOUT touching
    // Material.isSolid(), which requires the Paper block registry (only present on a live
    // server — off it, "No RegistryAccess implementation found").
    private final Predicate<Material> solid;
    // Dimension id → World, resolved lazily and cached. Worlds are stable for the
    // server lifetime, so a hit avoids re-scanning getWorlds() on every block probe.
    private final ConcurrentHashMap<Integer, World> worldByDim = new ConcurrentHashMap<>();

    public NmsTerrainView(Server server) {
        this(server, Material::isSolid);
    }

    /** Test seam: inject the material-solidity predicate (see the field's registry note). */
    NmsTerrainView(Server server, Predicate<Material> solid) {
        this.server = Objects.requireNonNull(server, "server");
        this.solid = Objects.requireNonNull(solid, "solid");
    }

    @Override
    public boolean isSolid(WorldPos pos) {
        World world = worldByDim.computeIfAbsent(pos.dimensionId(), this::resolveWorld);
        if (world == null) {
            // Unknown dimension → treat as non-solid (open void) rather than throwing;
            // a missing world must not crash a region tick.
            return false;
        }
        return solid.test(world.getBlockAt(pos.x(), pos.y(), pos.z()).getType());
    }

    /** Finds the loaded world whose name maps to {@code dimId}, or null if none. */
    private World resolveWorld(int dimId) {
        for (World w : server.getWorlds()) {
            if (DimensionIds.fromName(w.getName()) == dimId) {
                return w;
            }
        }
        return null;
    }
}
