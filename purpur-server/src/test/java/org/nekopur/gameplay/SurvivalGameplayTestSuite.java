package org.nekopur.gameplay;

import org.jspecify.annotations.NullMarked;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@NullMarked
@SelectClasses({GameplayConfigTest.class, EnchantingRulesTest.class, EnchantmentMenuTest.class, MendingRulesTest.class,
    MendingOrbTest.class, AnvilRulesTest.class, ArmorMendingRulesTest.class, EnchantmentMechanicsTest.class, VanishingRulesTest.class})
public class SurvivalGameplayTestSuite {
}
