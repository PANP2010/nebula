package org.nebula.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Compares two replays tick-by-tick to detect determinism violations.
 *
 * <p>Algorithm (arch doc §12.3, simplified):
 * <ol>
 *   <li>Given a reference replay (recorded from vanilla / Folia) and a candidate
 *       replay (from the Nebula DAG executor), compare the state hash at each tick.</li>
 *   <li>On the first mismatch, report the tick number and both hashes.</li>
 *   <li>Optionally binary-search for the exact failing tick if only the final
 *       hashes are available (not per-tick).</li>
 * </ol>
 */
public final class ReplayVerifier {

    private static final Logger LOG = Logger.getLogger(ReplayVerifier.class.getName());

    private ReplayVerifier() {
    }

    /**
     * Compares two replays tick-by-tick.
     *
     * @param reference  the "ground truth" replay (vanilla/Folia output)
     * @param candidate  the replay to verify (Nebula DAG output)
     * @return a verification report; empty mismatches means the replays match
     */
    public static VerificationResult verify(List<ReplayFrame> reference, List<ReplayFrame> candidate) {
        List<Mismatch> mismatches = new ArrayList<>();

        int maxTicks = Math.min(reference.size(), candidate.size());

        for (int i = 0; i < maxTicks; i++) {
            ReplayFrame ref  = reference.get(i);
            ReplayFrame cand = candidate.get(i);

            // Compare by list index (not tickNumber) — sprint uses logical ticks,
            // globalTick uses real server ticks; only the hash sequence matters.
            if (!java.util.Arrays.equals(ref.stateHash(), cand.stateHash())) {
                mismatches.add(new Mismatch(ref.tickNumber(), "state-hash",
                    ref.stateHashHex(), cand.stateHashHex()));
            }
        }

        if (reference.size() != candidate.size()) {
            mismatches.add(new Mismatch(-1, "replay-length",
                String.valueOf(reference.size()), String.valueOf(candidate.size())));
        }

        return new VerificationResult(mismatches, reference.size(), candidate.size());
    }

    /**
     * Binary-search for the first diverging tick between two state-hash sequences
     * (arch doc §12.3 Step 1).
     *
     * @param referenceHashes  state hashes in tick order from the reference
     * @param candidateHashes  state hashes in tick order from the candidate
     * @return the tick index (0-based) of the first mismatch, or -1 if identical
     */
    public static int binarySearchFirstMismatch(List<byte[]> referenceHashes, List<byte[]> candidateHashes) {
        int lo = 0;
        int hi = Math.min(referenceHashes.size(), candidateHashes.size()) - 1;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (java.util.Arrays.equals(referenceHashes.get(mid), candidateHashes.get(mid))) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (lo < Math.min(referenceHashes.size(), candidateHashes.size())) {
            return lo; // first mismatch
        }
        return -1; // all compared ticks match
    }

    public record Mismatch(long tickNumber, String field, String expected, String actual) {}

    public record VerificationResult(List<Mismatch> mismatches, int referenceTicks, int candidateTicks) {
        public boolean passed() {
            return mismatches.isEmpty();
        }

        public int mismatchCount() {
            return mismatches.size();
        }
    }
}
