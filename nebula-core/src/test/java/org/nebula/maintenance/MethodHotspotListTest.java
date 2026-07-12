package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.maintenance.MethodHotspotList.HotspotMethod;
import org.nebula.maintenance.MethodHotspotList.HotspotReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MethodHotspotList}. The parser is exercised
 * against synthetic async-profiler output (both flat and collapsed
 * formats) and the {@link HotspotReport}'s top-N behaviour is pinned
 * for the P1.5.4a acceptance.
 */
class MethodHotspotListTest {

    @Test
    void parseMethodNameSplitsClassAndMethod() {
        MethodHotspotList.ParsedName n =
            MethodHotspotList.parseMethodName("net.minecraft.world.entity.Entity.tick");
        assertEquals("net.minecraft.world.entity.Entity", n.className());
        assertEquals("tick", n.methodName());
        assertEquals("", n.descriptor());
    }

    @Test
    void parseMethodNameHandlesDescriptor() {
        MethodHotspotList.ParsedName n =
            MethodHotspotList.parseMethodName("Entity.tick()V");
        assertEquals("Entity", n.className());
        assertEquals("tick", n.methodName());
        assertEquals("()V", n.descriptor());
    }

    @Test
    void parseMethodNameHandlesNoDots() {
        MethodHotspotList.ParsedName n =
            MethodHotspotList.parseMethodName("__psynch_cvwait");
        assertEquals("", n.className());
        assertEquals("__psynch_cvwait", n.methodName());
    }

    @Test
    void flatProfileParsesTopN(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("flat.txt");
        Files.writeString(p,
            "Profiling for 30 seconds\n"
            + "Done\n"
            + "--- Execution profile ---\n"
            + "Total samples       : 226\n"
            + "\n"
            + "          ns  percent  samples  top\n"
            + "  ----------  -------  -------  ---\n"
            + "    36000000   15.93%       36  net.minecraft.world.entity.Entity.tick\n"
            + "    13000000    5.75%       13  io.papermc.paper.threadedregions.FoliaGlobalRegionScheduler.tick\n"
            + "    11000000    4.87%       11  ca.spottedleaf.moonrise.common.time.TickData.addDataFrom\n"
            + "     8000000    3.54%        8  mach_msg2_trap\n"
            + "     6000000    2.65%        6  io.papermc.paper.threadedregions.TickRegionScheduler$RegionScheduleHandle.runTick\n");
        MethodHotspotList list = new MethodHotspotList();
        HotspotReport report = list.fromFlatProfile(p, 3);

        assertEquals(3, report.methods().size());
        // Top by percent is the Entity.tick frame; the parser preserves
        // the full package-qualified class name.
        assertEquals("net.minecraft.world.entity.Entity",
            report.methods().get(0).className());
        assertEquals("tick", report.methods().get(0).methodName());
        assertEquals(15.93, report.methods().get(0).cumulativePercent(), 1e-9);
        // mach_msg2_trap has no class; not annotated
        HotspotMethod nativeFrame = report.methods().stream()
            .filter(m -> m.methodName().equals("mach_msg2_trap"))
            .findFirst().orElse(null);
        if (nativeFrame != null) {
            assertEquals("", nativeFrame.className());
            assertFalse(nativeFrame.annotated());
        }
        // Source file is recorded
        assertTrue(report.sourceFile().endsWith("flat.txt"));
    }

    @Test
    void collapsedFormatAggregatesByLeafFrame(@TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("profile.collapsed");
        Files.writeString(p,
            "Entity.tick;FoliaGlobalRegionScheduler.tick;RegionScheduleHandle.runTick 10\n"
            + "Entity.tick;FoliaGlobalRegionScheduler.tick 5\n"
            + "Entity.tick 3\n"
            + "mach_msg2_trap 2\n");
        MethodHotspotList list = new MethodHotspotList();
        HotspotReport report = list.fromCollapsed(p, 10);

        // Total: 20 samples. Entity.tick: 18/20 = 90.0%. mach_msg2_trap: 10.0%.
        HotspotMethod top = report.methods().get(0);
        assertEquals("Entity", top.className());
        assertEquals("tick", top.methodName());
        assertEquals(90.0, top.cumulativePercent(), 1e-9);
    }

    @Test
    void dispatchReadsByFileExtension(@TempDir Path tmp) throws IOException {
        Path flat = tmp.resolve("report.txt");
        Files.writeString(flat,
            "          ns  percent  samples  top\n"
            + "  ----------  -------  -------  ---\n"
            + "     1000000    1.00%        1  Entity.tick\n");
        MethodHotspotList list = new MethodHotspotList();
        HotspotReport report = list.fromFlamegraph(flat, 5);
        assertEquals(1, report.methods().size());
    }

    @Test
    void htmlFlamegraphIsRejectedWithClearError(@TempDir Path tmp) throws IOException {
        Path html = tmp.resolve("flame.html");
        Files.writeString(html, "<html><body>flamegraph</body></html>");
        MethodHotspotList list = new MethodHotspotList();
        try {
            list.fromFlamegraph(html, 5);
            assert false : "should have rejected .html";
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("HTML"));
        }
    }

    @Test
    void gapCountEqualsMethodsSizeMinusAnnotated() {
        // Build a synthetic report with no annotations to verify the
        // count math is honest: if the scanner reports nothing as
        // annotated, every method is a gap.
        HotspotReport r = new HotspotReport(
            java.time.Instant.now(), "synthetic", 3,
            List.of(
                new HotspotMethod("A", "m1", "", 10.0, false, ""),
                new HotspotMethod("B", "m2", "", 5.0, true, "@NebulaRW()"),
                new HotspotMethod("C", "m3", "", 1.0, false, "")),
            1, 2);
        assertEquals(2, r.gapCount());
        assertEquals(1, r.annotatedCount());
        assertEquals(1.0 / 3.0, r.coverageRatio(), 1e-9);
    }
}
