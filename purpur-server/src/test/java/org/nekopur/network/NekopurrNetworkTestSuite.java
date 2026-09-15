package org.nekopur.network;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@org.jspecify.annotations.NullMarked
@SelectClasses({NetworkConfigTest.class, PurroxyConnectionTest.class, BackendLifecycleTest.class,
    HandoffJournalTest.class, BackendPairingTest.class, BackendHandoffPredictionTest.class,
    ReleaseProtocolTest.class})
public class NekopurrNetworkTestSuite {}
