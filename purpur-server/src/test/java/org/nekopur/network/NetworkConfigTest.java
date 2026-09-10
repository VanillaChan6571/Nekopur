package org.nekopur.network;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@org.jspecify.annotations.NullMarked
class NetworkConfigTest {
    @TempDir
    Path directory;

    @Test
    void writesDedicatedConfigAndPreservesAdminChanges() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig defaults = NetworkConfig.load(file);
        assertFalse(defaults.enabled());
        assertEquals(50, defaults.safeLimit());
        assertEquals("hub", defaults.group());
        assertEquals("us-west", defaults.region());
        assertEquals(0, defaults.advertisedPort());
        assertEquals("auto", defaults.serverId());
        assertEquals("pairing", defaults.authentication());
        assertEquals("auto", defaults.advertisedHost());
        Files.writeString(file, Files.readString(file).replace("player-safe-limit: 50", "player-safe-limit: 73"));
        assertEquals(73, NetworkConfig.load(file).safeLimit());
    }

    @Test
    void readsAnUploadedChallengeFileAndLetsTheYamlOverrideIt() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig.load(file);
        assertEquals("", NetworkConfig.load(file).pairingToken());
        String uploaded = "p1." + "a".repeat(64) + "." + "b".repeat(43);
        Path challengeFile = this.directory.resolve(NetworkConfig.CHALLENGE_FILE);
        Files.writeString(challengeFile, uploaded + System.lineSeparator());
        NetworkConfig fromFile = NetworkConfig.load(file);
        assertEquals(uploaded, fromFile.pairingToken());
        assertEquals(challengeFile, fromFile.challengeFile());
        String pasted = "p1." + "a".repeat(64) + "." + "c".repeat(43);
        Files.writeString(file, Files.readString(file).replace("pairing-token: ''", "pairing-token: '" + pasted + "'"));
        assertEquals(pasted, NetworkConfig.load(file).pairingToken());
    }

    @Test
    void rejectsAnOversizedChallengeFile() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig.load(file);
        Files.writeString(this.directory.resolve(NetworkConfig.CHALLENGE_FILE), "p".repeat(4097));
        assertThrows(java.io.IOException.class, () -> NetworkConfig.load(file));
    }

    @Test
    void rejectsWorldTraversalAndWildcardDestinations() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig.load(file);
        String defaults = Files.readString(file);
        for (String map : new String[]{"../Christmas", "..", "C:\\world", "a/b"}) {
            Files.writeString(file, defaults.replace("map: none", "map: '" + map + "'"));
            assertThrows(IllegalArgumentException.class, () -> NetworkConfig.load(file));
        }
        Files.writeString(file, defaults.replace("ip: 127.0.0.1", "ip: 0.0.0.0"));
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.load(file));
    }

    @Test
    void acceptsNumberedPatternsAndLegacyCertificateConfig() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig.load(file);
        Files.writeString(file, Files.readString(file).replace("name: auto", "name: hub-*"));
        assertEquals("hub-*", NetworkConfig.load(file).serverId());
        Files.writeString(file, "identity:\n  server-id: hub-7\ntls:\n  key-store: existing.p12\n");
        NetworkConfig legacy = NetworkConfig.load(file);
        assertEquals("certificates", legacy.authentication());
        assertEquals("hub-7", legacy.serverId());
        assertFalse(legacy.startSleeping());
    }

    @Test
    void preservesCaseOfNamedWorlds() throws Exception {
        Path file = this.directory.resolve("Nekopurr.yaml");
        NetworkConfig.load(file);
        Files.writeString(file, Files.readString(file).replace("map: none", "map: Christmas"));
        assertEquals("Christmas", NetworkConfig.load(file).map());
    }
}
