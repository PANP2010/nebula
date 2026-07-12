package org.nebula.player.ai;

import org.nebula.core.state.WorldPos;
import org.nebula.core.math.Vec3;
import java.util.*;

/**
 * A* pathfinder for mob navigation.
 * Finds the shortest path between two positions avoiding solid blocks.
 */
public final class PathfinderAStar {

    public record Node(WorldPos pos, double g, double h, Node parent) 
        implements Comparable<Node> {
        public double f() { return g + h; }
        @Override public int compareTo(Node o) { return Double.compare(f(), o.f()); }
    }

    public record Path(List<WorldPos> positions, int cost, boolean reachable) {}

    /**
     * Find path from start to goal using A*.
     * @param start block position
     * @param goal block position
     * @param maxIterations cap on nodes to explore (for performance)
     */
    public static Path find(WorldPos start, WorldPos goal, int maxIterations,
                            java.util.function.Function<WorldPos, Boolean> isSolid) {
        
        if (start.equals(goal)) return new Path(List.of(start), 0, true);
        
        PriorityQueue<Node> open = new PriorityQueue<>();
        Set<WorldPos> closed = new HashSet<>();
        open.add(new Node(start, 0, heuristic(start, goal), null));
        
        int iterations = 0;
        while (!open.isEmpty() && iterations < maxIterations) {
            iterations++;
            Node current = open.poll();
            
            if (current.pos().equals(goal)) {
                return new Path(reconstruct(current), (int) current.g(), true);
            }
            
            closed.add(current.pos());
            
            for (WorldPos neighbor : neighbors(current.pos(), isSolid)) {
                if (closed.contains(neighbor)) continue;
                
                double tentativeG = current.g() + 1;
                Node existing = findInOpen(open, neighbor);
                if (existing != null && tentativeG < existing.g()) {
                    open.remove(existing);
                }
                if (existing == null || tentativeG < existing.g()) {
                    open.add(new Node(neighbor, tentativeG, heuristic(neighbor, goal), current));
                }
            }
        }
        return new Path(List.of(), -1, false); // not reachable
    }

    private static double heuristic(WorldPos a, WorldPos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }

    private static List<WorldPos> neighbors(WorldPos pos, java.util.function.Function<WorldPos, Boolean> isSolid) {
        List<WorldPos> result = new ArrayList<>();
        int[][] deltas = {
            {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, {0,1,0}, {0,-1,0},
            {1,1,0}, {-1,1,0}, {1,-1,0}, {-1,-1,0}, {1,0,1}, {-1,0,1}
        };
        for (int[] d : deltas) {
            WorldPos np = new WorldPos(pos.dimensionId(), pos.x() + d[0], pos.y() + d[1], pos.z() + d[2]);
            if (!isSolid.apply(np)) result.add(np);
        }
        return result;
    }

    private static Node findInOpen(PriorityQueue<Node> open, WorldPos pos) {
        for (Node n : open) {
            if (n.pos().equals(pos)) return n;
        }
        return null;
    }

    private static List<WorldPos> reconstruct(Node goal) {
        List<WorldPos> path = new ArrayList<>();
        Node current = goal;
        while (current != null) {
            path.add(current.pos());
            current = current.parent();
        }
        Collections.reverse(path);
        return path;
    }
}
