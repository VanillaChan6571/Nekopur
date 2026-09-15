package org.nekopur.compat;

import org.jspecify.annotations.NullMarked;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@NullMarked
@SelectClasses(FuelOverridesTest.class)
public class FuelOverridesTestSuite {
}
