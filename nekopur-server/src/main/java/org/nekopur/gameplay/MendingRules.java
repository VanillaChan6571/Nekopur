package org.nekopur.gameplay;

import org.jspecify.annotations.NullMarked;

/** Integer repair budgets and XP accounting for survival mending. */
@NullMarked
public final class MendingRules {
    private MendingRules() {
    }

    public static int repairBudget(int vanillaBudget, double multiplier) {
        if (vanillaBudget <= 0 || !Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(vanillaBudget * multiplier));
    }

    /** Round partial repairs up so a single repaired point never generates free XP. */
    public static int consumedExperience(int repair, int experience, int budget) {
        if (repair <= 0 || experience <= 0 || budget <= 0) {
            return 0;
        }
        return (int) Math.min(experience, ((long) repair * experience + budget - 1L) / budget);
    }
}
