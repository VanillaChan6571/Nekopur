package org.nekopur.gameplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class MendingRulesTest {
    @Test
    void halfEfficiencyRepairsOneDurabilityPerVanillaExperiencePoint() {
        assertEquals(7, MendingRules.repairBudget(14, 0.5));
        assertEquals(7, MendingRules.consumedExperience(7, 7, 7));
    }

    @Test
    void partialRepairStillCostsExperience() {
        assertEquals(1, MendingRules.consumedExperience(1, 7, 14));
        assertEquals(1, MendingRules.consumedExperience(1, 7, 7));
    }

    @Test
    void zeroEfficiencyAndTinyBudgetsCannotDivideByZeroOrSwallowExperience() {
        assertEquals(0, MendingRules.repairBudget(20, 0.0));
        assertEquals(0, MendingRules.repairBudget(2, 0.1));
        assertEquals(0, MendingRules.consumedExperience(0, 10, 0));
    }

    @Test
    void repeatedPartialRepairsMakeProgressAndNeverSpendMoreThanTheOrb() {
        for (int experience = 1; experience < 100; experience++) {
            int spent = MendingRules.consumedExperience(1, experience, experience * 2);
            assertTrue(spent > 0 && spent <= experience);
        }
    }

    @Test
    void largeRepairAndPluginOverrideDoNotOverflowOrCreateNegativeExperience() {
        assertEquals(Integer.MAX_VALUE, MendingRules.consumedExperience(Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, MendingRules.repairBudget(Integer.MAX_VALUE, 2));
    }
}
