package org.nekopur.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@org.jspecify.annotations.NullMarked
class PurroxyConnectionTest {
    @TempDir
    static Path directory;
    static SSLContext context;
    static String certificatePin;

    @BeforeAll
    static void certificates() throws Exception {
        Path storeFile = directory.resolve("test.p12");
        // Ephemeral test credentials, never used by a real backend.
        String password = "test-only-password";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", storeFile.toString(), "-storepass", password)
            .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream input = Files.newInputStream(storeFile)) {
            store.load(input, password.toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        certificatePin = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(store.getCertificate("test").getEncoded()));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    }

    @Test
    void pairsByCertificatePinAndReconnectsWithoutTokenOrClientCertificate() throws Exception {
        Path identity = directory.resolve("paired/identity.json");
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10000);
            for (int attempt = 0; attempt < 2; attempt++) {
                NetworkConfig config = pairingConfig(listener, identity,
                    attempt == 0 ? "p1." + certificatePin + "." + "b".repeat(43) : "");
                try (PurroxyConnection backend = new PurroxyConnection(config, 25580, 73,
                    () -> new PurroxyConnection.Snapshot("SLEEPING", 0), request -> {}, Logger.getLogger("Nekopurr-pairing-test"))) {
                    backend.enableSleep(request -> CompletableFuture.completedFuture(true));
                    backend.start();
                    try (SSLSocket secure = accept(listener, false)) {
                        BufferedReader input = new BufferedReader(new InputStreamReader(secure.getInputStream(), StandardCharsets.UTF_8));
                        JsonObject resume = JsonParser.parseString(input.readLine()).getAsJsonObject();
                        assertEquals(attempt == 0, resume.getAsJsonObject("pairing").has("token"));
                        assertEquals(attempt != 0, resume.getAsJsonObject("pairing").has("credential"));
                        UUID session = UUID.randomUUID();
                        JsonObject registration = new JsonObject();
                        registration.addProperty("type", "registered");
                        registration.addProperty("session", session.toString());
                        registration.addProperty("heartbeatSeconds", 5);
                        registration.addProperty("leaseSeconds", 20);
                        registration.addProperty("serverId", "hub-1");
                        registration.addProperty("credential", "c".repeat(43));
                        write(secure, registration.toString());
                        assertEquals("heartbeat", JsonParser.parseString(input.readLine()).getAsJsonObject().get("type").getAsString());
                        assertEquals("c".repeat(43), new BackendPairing(identity, "").authentication().get("credential").getAsString());
                        write(secure, "{\"type\":\"sleep\",\"session\":\"" + session + "\",\"request\":\"" + UUID.randomUUID() + "\"}");
                        JsonObject sleep = JsonParser.parseString(input.readLine()).getAsJsonObject();
                        assertEquals("sleep-result", sleep.get("type").getAsString());
                        assertTrue(sleep.get("accepted").getAsBoolean());
                    }
                }
            }
        }
    }

    @Test
    void pairingRejectsProxyWithWrongCertificatePin() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10000);
            NetworkConfig config = pairingConfig(listener, directory.resolve("wrong-pin/identity.json"),
                "p1." + "0".repeat(64) + "." + "b".repeat(43));
            try (PurroxyConnection backend = new PurroxyConnection(config, 25580, 73,
                () -> new PurroxyConnection.Snapshot("SLEEPING", 0), request -> {}, Logger.getLogger("Nekopurr-pairing-test"))) {
                backend.start();
                assertThrows(java.io.IOException.class, () -> {
                    try (SSLSocket ignored = accept(listener, false)) {
                        fail("TLS accepted a different proxy certificate");
                    }
                });
            }
        }
    }

    private static NetworkConfig pairingConfig(ServerSocket listener, Path identity, String token) {
        return new NetworkConfig(true, "127.0.0.1", listener.getLocalPort(), "hub-*", "auto", 0, "hub", "none",
            50, "us-west", true, directory.resolve("unused.p12"), "unused", directory.resolve("unused.crt"),
            "pairing", token, identity, directory.resolve(NetworkConfig.CHALLENGE_FILE));
    }

    @Test
    void advertisesActualLimitsAndReceivesWakeWithoutPlayers() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10000);
            CompletableFuture<UUID> wake = new CompletableFuture<>();
            try (PurroxyConnection backend = connection(listener, "localhost", wake)) {
                backend.start();
                try (SSLSocket secure = accept(listener)) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(secure.getInputStream(), StandardCharsets.UTF_8));
                    JsonObject resume = JsonParser.parseString(input.readLine()).getAsJsonObject();
                    assertEquals(1, resume.get("version").getAsInt());
                    assertEquals(73, resume.getAsJsonObject("resume").get("hardLimit").getAsInt());
                    assertEquals(25580, resume.getAsJsonObject("resume").get("port").getAsInt());
                    UUID session = UUID.randomUUID();
                    write(secure, "{\"type\":\"registered\",\"session\":\"" + session
                        + "\",\"heartbeatSeconds\":5,\"leaseSeconds\":20}");
                    JsonObject heartbeat = JsonParser.parseString(input.readLine()).getAsJsonObject();
                    assertEquals("SLEEPING", heartbeat.get("state").getAsString());
                    assertEquals(0, heartbeat.get("players").getAsInt());
                    assertEquals(0, heartbeat.get("sequence").getAsLong());
                    UUID request = UUID.randomUUID();
                    write(secure, "{\"type\":\"wake\",\"session\":\"" + session + "\",\"request\":\"" + request + "\"}");
                    assertEquals(request, wake.get(3, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void rejectsWrongCertificateHostname() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10000);
            try (PurroxyConnection backend = connection(listener, "127.0.0.1", new CompletableFuture<>())) {
                backend.start();
                // Certificate has DNS localhost only; connecting by IP must fail verification.
                assertThrows(java.io.IOException.class, () -> {
                    try (SSLSocket ignored = accept(listener)) {
                        fail("TLS accepted a certificate without the requested IP SAN");
                    }
                });
            }
        }
    }

    @Test
    void rejectsWakeForAnotherSession() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(10000);
            CompletableFuture<UUID> wake = new CompletableFuture<>();
            try (PurroxyConnection backend = connection(listener, "localhost", wake)) {
                backend.start();
                try (SSLSocket secure = accept(listener)) {
                    BufferedReader input = new BufferedReader(new InputStreamReader(secure.getInputStream(), StandardCharsets.UTF_8));
                    input.readLine();
                    write(secure, "{\"type\":\"registered\",\"session\":\"" + UUID.randomUUID()
                        + "\",\"heartbeatSeconds\":5,\"leaseSeconds\":20}");
                    input.readLine();
                    write(secure, "{\"type\":\"wake\",\"session\":\"" + UUID.randomUUID()
                        + "\",\"request\":\"" + UUID.randomUUID() + "\"}");
                    assertNull(input.readLine());
                    assertFalse(wake.isDone());
                }
            }
        }
    }

    private static PurroxyConnection connection(ServerSocket listener, String host, CompletableFuture<UUID> wake) {
        NetworkConfig config = new NetworkConfig(true, host, listener.getLocalPort(), "hub-1", "localhost",
            0, "hub", "none", 50, "us-west", true, directory.resolve("test.p12"), "unused", directory.resolve("unused"));
        return new PurroxyConnection(config, 25580, 73, () -> new PurroxyConnection.Snapshot("SLEEPING", 0),
            wake::complete, Logger.getLogger("Nekopurr-test"), context);
    }

    private static SSLSocket accept(ServerSocket listener) throws Exception {
        return accept(listener, true);
    }

    private static SSLSocket accept(ServerSocket listener, boolean clientCertificate) throws Exception {
        Socket socket = listener.accept();
        socket.setSoTimeout(10000);
        assertEquals("PURROXY\n", new String(socket.getInputStream().readNBytes(8), StandardCharsets.US_ASCII));
        SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), true);
        secure.setUseClientMode(false);
        secure.setNeedClientAuth(clientCertificate);
        secure.setEnabledProtocols(new String[]{"TLSv1.3"});
        try {
            secure.startHandshake();
            return secure;
        } catch (Exception failure) {
            secure.close();
            throw failure;
        }
    }

    private static void write(SSLSocket socket, String message) throws Exception {
        socket.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
}
