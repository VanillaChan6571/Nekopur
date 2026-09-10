package org.nekopur.network;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@NullMarked
class BackendPairingTest {
    @TempDir Path directory;

    @Test
    void persistsInstanceThenReplacesEnrollmentTokenWithCredential() throws Exception {
        Path file = this.directory.resolve("identity.json");
        String token = "p1." + "a".repeat(64) + "." + "b".repeat(43);
        BackendPairing initial = new BackendPairing(file, token);
        assertEquals(initial.authentication(), new BackendPairing(file, token).authentication());
        JsonObject reply = new JsonObject();
        reply.addProperty("serverId", "hub-1");
        reply.addProperty("credential", "c".repeat(43));
        assertEquals("hub-1", initial.registered(reply));
        BackendPairing restarted = new BackendPairing(file, "");
        assertEquals(initial.authentication(), restarted.authentication());
        assertFalse(restarted.authentication().has("token"));
        assertFalse(Files.readString(file).contains("b".repeat(43)));
        reply.addProperty("serverId", "hub-2");
        assertThrows(IOException.class, () -> restarted.registered(reply));
    }

    @Test
    void replacementTokenCanFixFailedEnrollmentWithoutChangingInstance() throws Exception {
        Path file = this.directory.resolve("identity.json");
        String prefix = "p1." + "a".repeat(64) + ".";
        BackendPairing initial = new BackendPairing(file, prefix + "b".repeat(43));
        BackendPairing corrected = new BackendPairing(file, prefix + "c".repeat(43));
        assertEquals(initial.authentication().get("instance"), corrected.authentication().get("instance"));
        assertEquals("c".repeat(43), corrected.authentication().get("token").getAsString());
    }

    @Test
    void discardsTheSharedChallengeOnlyAfterTheCredentialIsStored() throws Exception {
        Path file = this.directory.resolve("identity.json");
        Path challengeFile = this.directory.resolve(NetworkConfig.CHALLENGE_FILE);
        String challenge = "p1." + "a".repeat(64) + "." + "b".repeat(43);
        Files.writeString(challengeFile, challenge);
        BackendPairing pairing = new BackendPairing(file, challenge, challengeFile);
        pairing.discardChallenge();
        assertTrue(Files.exists(challengeFile), "The challenge is still needed until enrollment succeeds");
        JsonObject reply = new JsonObject();
        reply.addProperty("serverId", "hub-1");
        reply.addProperty("credential", "c".repeat(43));
        assertEquals("hub-1", pairing.registered(reply));
        pairing.discardChallenge();
        assertFalse(Files.exists(challengeFile));
        pairing.discardChallenge(); // A repeated discard after a lost acknowledgement must not fail.
    }

    @Test
    void missingTokenAndCorruptIdentityFailWithNoReplacement() throws Exception {
        Path file = this.directory.resolve("identity.json");
        assertThrows(IOException.class, () -> new BackendPairing(file, ""));
        Files.writeString(file, "{invalid");
        assertThrows(IOException.class, () -> new BackendPairing(file, "p1." + "a".repeat(64) + "." + "b".repeat(43)));
        assertEquals("{invalid", Files.readString(file));
    }
}
