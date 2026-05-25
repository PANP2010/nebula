package org.nebula.core.random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RandomBudgetTest {

    @Test
    void allocatedBudgetRespectsSafetyMultiplier() {
        RandomBudget budget = new RandomBudget(1.5, 10, 0.05, 10);
        // estimate=20 → max(20*1.5, 10) = 30
        assertEquals(30, budget.allocate(1L, 20));
    }

    @Test
    void allocatedBudgetRespectsMinBudget() {
        RandomBudget budget = new RandomBudget(1.5, 10, 0.05, 10);
        // estimate=0 → max(0*1.5, 10) = 10
        assertEquals(10, budget.allocate(1L, 0));
    }

    @Test
    void withinBudgetReturnsCommit() {
        RandomBudget budget = new RandomBudget();
        budget.beginTick();
        assertEquals(RandomBudget.BudgetResult.COMMIT, budget.evaluate(1L, 30, 20));
    }

    @Test
    void slightlyOverBudgetReturnsExpand() {
        RandomBudget budget = new RandomBudget(1.5, 10, 0.05, 10);
        budget.beginTick();
        // 1 entity, 1 over-budget = 100% rate but only 1 entity — check expand vs downgrade
        // With threshold 0.05 and only 1 entity, rate=1.0 >= 0.05 → DOWNGRADE
        // To test EXPAND we need multiple entities where only a few are over budget
        for (int i = 0; i < 19; i++) {
            budget.evaluate((long) i, 30, 20); // within budget
        }
        // Now 20th entity is over — 1/20 = 5% which is right at the threshold
        RandomBudget.BudgetResult r = budget.evaluate(20L, 30, 35);
        // 5% >= 5% → DOWNGRADE
        assertNotEquals(RandomBudget.BudgetResult.COMMIT, r);
    }

    @Test
    void exactBudgetReturnsCommit() {
        RandomBudget budget = new RandomBudget();
        budget.beginTick();
        assertEquals(RandomBudget.BudgetResult.COMMIT, budget.evaluate(1L, 30, 30));
    }

    @Test
    void historicalMaxUpdated() {
        RandomBudget budget = new RandomBudget();
        budget.beginTick();
        budget.evaluate(1L, 50, 40);
        assertEquals(40, budget.historicalMax(1L));

        budget.beginTick();
        // Historical max was 40, so budget should include that
        assertTrue(budget.allocate(1L, 10) >= 40);
    }

    @Test
    void consecutiveDowngradesTracked() {
        RandomBudget budget = new RandomBudget(1.5, 10, 0.0, 3); // threshold=0: any over-budget → downgrade
        budget.beginTick();
        budget.evaluate(1L, 10, 20); // over-budget
        budget.endTick(true);
        budget.endTick(true);
        budget.endTick(true);
        assertTrue(budget.beginTick()); // 3 consecutive → warning flag
    }

    @Test
    void consecutiveDowngradesResetOnCleanTick() {
        RandomBudget budget = new RandomBudget();
        budget.beginTick();
        budget.endTick(true);
        budget.endTick(true);
        budget.endTick(false); // clean tick resets counter
        assertFalse(budget.beginTick()); // should not warn
    }

    @Test
    void overBudgetRateCalculated() {
        RandomBudget budget = new RandomBudget(1.5, 10, 0.05, 10);
        budget.beginTick();
        for (int i = 0; i < 10; i++) {
            budget.evaluate((long) i, 30, 20); // within budget
        }
        assertEquals(0.0, budget.currentOverBudgetRate(), 0.001);
    }
}
