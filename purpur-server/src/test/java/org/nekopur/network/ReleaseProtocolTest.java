package org.nekopur.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
class ReleaseProtocolTest {
    @Test
    void loginVersionMatchesReleasedMinecraft263() {
        SharedConstants.tryDetectVersion();
        WorldVersion version = SharedConstants.getCurrentVersion();

        // The login handler compares the client's protocol to this metadata.
        assertEquals(777, version.protocolVersion());
        assertEquals(777, SharedConstants.getProtocolVersion());
        assertEquals("26.3", version.name());
        assertTrue(version.stable(), "The jar must contain release version metadata");
    }
}
