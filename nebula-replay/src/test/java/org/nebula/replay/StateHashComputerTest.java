package org.nebula.replay;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Determinism contract tests for {@link StateHashComputer}.
 *
 * <p>The computer is the load-bearing primitive behind replay verification:
 * if its output ever depends on map iteration order, two structurally
 * equivalent replays would produce different hashes and the entire DG2/DG3
 * verification chain breaks.
 */
class StateHashComputerTest {

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] longBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    @Test
    void sameInputProducesSameHash() {
        Map<String, byte[]> blocks = Map.of("0:0,0", bytes("stone"));
        Map<String, byte[]> bes = Map.of("0:0,0,0", bytes("furnace"));
        Map<String, byte[]> ents = Map.of("uuid-1", bytes("zombie"));
        Map<String, byte[]> globals = Map.of("gameTime", longBytes(100));

        byte[] a = StateHashComputer.compute(blocks, bes, ents, globals);
        byte[] b = StateHashComputer.compute(blocks, bes, ents, globals);

        assertArrayEquals(a, b);
        assertEquals(32, a.length);
    }

    @Test
    void hashIsIndependentOfMapInsertionOrder() {
        // Insertion order: a, b, c
        LinkedHashMap<String, byte[]> insOrder = new LinkedHashMap<>();
        insOrder.put("a", bytes("alpha"));
        insOrder.put("b", bytes("bravo"));
        insOrder.put("c", bytes("charlie"));

        // Reverse insertion order: c, b, a
        LinkedHashMap<String, byte[]> revOrder = new LinkedHashMap<>();
        revOrder.put("c", bytes("charlie"));
        revOrder.put("b", bytes("bravo"));
        revOrder.put("a", bytes("alpha"));

        byte[] h1 = StateHashComputer.compute(insOrder, Map.of(), Map.of(), Map.of());
        byte[] h2 = StateHashComputer.compute(revOrder, Map.of(), Map.of(), Map.of());

        assertArrayEquals(h1, h2,
            "TreeMap re-sort must make hash insensitive to source map iteration order");
    }

    @Test
    void hashUsesAllFourCategories() {
        byte[] h1 = StateHashComputer.compute(Map.of("k", bytes("v")), Map.of(), Map.of(), Map.of());
        byte[] h2 = StateHashComputer.compute(Map.of(), Map.of("k", bytes("v")), Map.of(), Map.of());
        byte[] h3 = StateHashComputer.compute(Map.of(), Map.of(), Map.of("k", bytes("v")), Map.of());
        byte[] h4 = StateHashComputer.compute(Map.of(), Map.of(), Map.of(), Map.of("k", bytes("v")));

        // Same key/value in different categories must hash differently — otherwise
        // a block named "k" would be indistinguishable from an entity named "k".
        assertFalse(java.util.Arrays.equals(h1, h2));
        assertFalse(java.util.Arrays.equals(h1, h3));
        assertFalse(java.util.Arrays.equals(h1, h4));
        assertFalse(java.util.Arrays.equals(h2, h3));
        assertFalse(java.util.Arrays.equals(h2, h4));
        assertFalse(java.util.Arrays.equals(h3, h4));
    }

    @Test
    void changingValueChangesHash() {
        Map<String, byte[]> base = Map.of("gameTime", longBytes(100));
        Map<String, byte[]> bumped = Map.of("gameTime", longBytes(101));

        byte[] h1 = StateHashComputer.compute(Map.of(), Map.of(), Map.of(), base);
        byte[] h2 = StateHashComputer.compute(Map.of(), Map.of(), Map.of(), bumped);
        assertFalse(java.util.Arrays.equals(h1, h2));
    }

    @Test
    void emptyVsSingleEntryDiffer() {
        byte[] empty = StateHashComputer.compute(Map.of(), Map.of(), Map.of(), Map.of());
        byte[] one = StateHashComputer.compute(Map.of("k", bytes("v")), Map.of(), Map.of(), Map.of());
        assertFalse(java.util.Arrays.equals(empty, one));
    }

    @Test
    void lengthPrefixPreventsCollision() {
        // Without the 4-byte length prefix between key and value, the pairs
        // ("ab", "cd") and ("a", "bcd") would hash identically.
        byte[] h1 = StateHashComputer.compute(Map.of("ab", bytes("cd")), Map.of(), Map.of(), Map.of());
        byte[] h2 = StateHashComputer.compute(Map.of("a", bytes("bcd")), Map.of(), Map.of(), Map.of());
        assertFalse(java.util.Arrays.equals(h1, h2),
            "Length prefix must disambiguate concatenation boundaries");
    }

    @Test
    void seedHashIsDeterministic() {
        byte[] h1 = StateHashComputer.computeSeedHash(123456789L);
        byte[] h2 = StateHashComputer.computeSeedHash(123456789L);
        byte[] h3 = StateHashComputer.computeSeedHash(123456790L);
        assertArrayEquals(h1, h2);
        assertFalse(java.util.Arrays.equals(h1, h3));
    }

    @Test
    void largeSnapshotIsDeterministic() {
        // 256 chunks × 4 categories — covers TreeMap performance + hash
        // stability under load.
        Map<String, byte[]> blocks = new HashMap<>();
        Map<String, byte[]> bes = new HashMap<>();
        Map<String, byte[]> ents = new HashMap<>();
        for (int i = 0; i < 256; i++) {
            blocks.put("0:" + i + ",0", longBytes(i));
            bes.put("0:" + i + ",0,0", longBytes(i * 2L));
            ents.put("uuid-" + i, longBytes(i * 3L));
        }
        Map<String, byte[]> globals = Map.of("gameTime", longBytes(42));

        byte[] h1 = StateHashComputer.compute(blocks, bes, ents, globals);
        byte[] h2 = StateHashComputer.compute(
            new TreeMap<>(blocks), new TreeMap<>(bes), new TreeMap<>(ents), new TreeMap<>(globals)
        );
        assertArrayEquals(h1, h2);
    }
}
