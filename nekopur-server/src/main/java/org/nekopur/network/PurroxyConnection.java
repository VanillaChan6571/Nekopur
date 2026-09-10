package org.nekopur.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Player-independent authenticated TLS control connection. Never calls world APIs. */
@NullMarked
public final class PurroxyConnection implements AutoCloseable {
    public record Snapshot(String state, int players) {}

    private final NetworkConfig config;
    private final JsonObject resume;
    private final SSLContext tls;
    private final @Nullable BackendPairing pairing;
    private final Supplier<Snapshot> snapshot;
    private final Consumer<UUID> wake;
    private final Logger logger;
    private final Thread worker;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Nekopurr-Purroxy-write-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private volatile long writeStarted;
    private volatile @Nullable Socket activeSocket;
    private long lastWarning;
    private volatile @Nullable UUID activeSession;
    private @Nullable Function<JsonObject, CompletableFuture<JsonObject>> handoff;
    private @Nullable Function<UUID, CompletableFuture<Boolean>> sleep;
    private Consumer<String> assignedName = name -> {};
    private final ArrayBlockingQueue<JsonObject> replies = new ArrayBlockingQueue<>(64);

    public void enableHandoff(JsonObject capabilities, Function<JsonObject, CompletableFuture<JsonObject>> handler) {
        this.resume.add("handoff", capabilities.deepCopy());
        this.handoff = handler;
    }

    public void enableSleep(Function<UUID, CompletableFuture<Boolean>> handler) {
        this.sleep = handler;
    }

    public void onAssignedName(Consumer<String> handler) {
        this.assignedName = handler;
    }

    public PurroxyConnection(NetworkConfig config, int listeningPort, int hardLimit,
                             Supplier<Snapshot> snapshot, Consumer<UUID> wake, Logger logger) throws Exception {
        this(config, listeningPort, hardLimit, snapshot, wake, logger,
            config.authentication().equals("pairing")
                ? new BackendPairing(config.identityFile(), config.pairingToken(), config.challengeFile()) : (BackendPairing) null);
    }

    private PurroxyConnection(NetworkConfig config, int listeningPort, int hardLimit,
                             Supplier<Snapshot> snapshot, Consumer<UUID> wake, Logger logger, @Nullable BackendPairing pairing) throws Exception {
        this(config, listeningPort, hardLimit, snapshot, wake, logger, pairing == null ? createTls(config) : pairing.tls(), pairing);
    }

    PurroxyConnection(NetworkConfig config, int listeningPort, int hardLimit,
                      Supplier<Snapshot> snapshot, Consumer<UUID> wake, Logger logger, SSLContext tls) {
        this(config, listeningPort, hardLimit, snapshot, wake, logger, tls, null);
    }

    private PurroxyConnection(NetworkConfig config, int listeningPort, int hardLimit,
                      Supplier<Snapshot> snapshot, Consumer<UUID> wake, Logger logger, SSLContext tls, @Nullable BackendPairing pairing) {
        this.config = config;
        this.snapshot = snapshot;
        this.wake = wake;
        this.logger = logger;
        this.tls = tls;
        this.pairing = pairing;
        JsonObject advertisement = new JsonObject();
        advertisement.addProperty("serverId", config.serverId());
        advertisement.addProperty("incarnation", UUID.randomUUID().toString());
        advertisement.addProperty("host", config.advertisedHost());
        advertisement.addProperty("port", config.advertisedPort() == 0 ? listeningPort : config.advertisedPort());
        advertisement.addProperty("group", config.group());
        advertisement.addProperty("map", config.map());
        advertisement.addProperty("safeLimit", config.safeLimit());
        advertisement.addProperty("hardLimit", hardLimit);
        advertisement.addProperty("region", config.region());
        this.resume = new JsonObject();
        this.resume.addProperty("type", "resume");
        this.resume.addProperty("version", 1);
        this.resume.add("resume", advertisement);
        this.worker = new Thread(this::run, "Nekopurr-Purroxy-control");
        this.worker.setDaemon(true);
    }

    public void start() {
        this.watchdog.scheduleAtFixedRate(() -> {
            long started = this.writeStarted;
            if (started != 0 && System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(5)) {
                closeSocket();
            }
        }, 1, 1, TimeUnit.SECONDS);
        this.worker.start();
    }

    private static SSLContext createTls(NetworkConfig config) throws Exception {
        String password = System.getenv(config.passwordEnvironment());
        if (password == null) {
            throw new IllegalArgumentException("Set the environment variable named by tls.password-environment");
        }
        char[] secret = password.toCharArray();
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        try (InputStream input = Files.newInputStream(config.keyStore())) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, secret);
            keys.init(store, secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        try (InputStream input = Files.newInputStream(config.proxyCa())) {
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                anchors.setCertificateEntry("proxy-ca-" + index++, certificate);
            }
            if (index == 0) {
                throw new IllegalArgumentException("tls.proxy-ca contains no certificates");
            }
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private void run() {
        while (!this.closed) {
            try {
                connect();
            } catch (Exception failure) {
                if (!this.closed && (this.lastWarning == 0
                    || System.nanoTime() - this.lastWarning >= TimeUnit.SECONDS.toNanos(30))) {
                    this.lastWarning = System.nanoTime();
                    this.logger.warning("Purroxy control connection unavailable: " + failure.getMessage());
                }
            } finally {
                this.activeSession = null;
                this.replies.clear();
                closeSocket();
            }
            if (!this.closed) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void connect() throws IOException {
        try (Socket socket = new Socket()) {
            this.activeSocket = socket;
            if (this.closed) {
                return;
            }
            socket.connect(new InetSocketAddress(this.config.proxyHost(), this.config.proxyPort()), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("PURROXY\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try (SSLSocket secure = (SSLSocket) this.tls.getSocketFactory()
                .createSocket(socket, this.config.proxyHost(), this.config.proxyPort(), true)) {
                secure.setUseClientMode(true);
                secure.setEnabledProtocols(new String[]{"TLSv1.3"});
                SSLParameters parameters = secure.getSSLParameters();
                if (this.pairing == null) {
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                } // Pairing verifies the exact certificate pin supplied out-of-band in the token.
                secure.setSSLParameters(parameters);
                secure.startHandshake();
                secure.setSoTimeout(250);
                JsonObject registration = this.resume.deepCopy();
                if (this.pairing != null) {
                    registration.add("pairing", this.pairing.authentication());
                }
                send(secure, registration);
                receive(secure);
            }
        }
    }

    private void receive(SSLSocket socket) throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        UUID session = null;
        long sequence = 0;
        long nextHeartbeat = 0;
        long nextReply = 0;
        long registrationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long frameStarted = 0;
        long messageWindow = System.nanoTime();
        int messages = 0;
        while (!this.closed) {
            long now = System.nanoTime();
            if (session == null && now - registrationDeadline >= 0) {
                throw new IOException("Purroxy registration timed out");
            }
            if (frameStarted != 0 && now - frameStarted >= TimeUnit.SECONDS.toNanos(10)) {
                throw new IOException("Purroxy control frame timed out");
            }
            if (session != null && now - nextHeartbeat >= 0) {
                Snapshot current = this.snapshot.get();
                JsonObject heartbeat = new JsonObject();
                heartbeat.addProperty("type", "heartbeat");
                heartbeat.addProperty("session", session.toString());
                heartbeat.addProperty("sequence", sequence++);
                heartbeat.addProperty("state", current.state());
                heartbeat.addProperty("players", current.players());
                heartbeat.add("admitted", new JsonArray());
                send(socket, heartbeat);
                nextHeartbeat = now + TimeUnit.SECONDS.toNanos(5);
                nextReply = now + TimeUnit.MILLISECONDS.toNanos(75);
            } else if (session != null && now - nextReply >= 0) {
                JsonObject reply = this.replies.poll();
                if (reply != null && session.toString().equals(reply.get("session").getAsString())) {
                    send(socket, reply);
                    nextReply = now + TimeUnit.MILLISECONDS.toNanos(75);
                }
            }
            int value;
            try {
                value = input.read();
            } catch (SocketTimeoutException timeout) {
                continue;
            }
            if (value < 0) {
                throw new EOFException("Purroxy closed the control session");
            }
            if (value != '\n') {
                if (frame.size() >= 65536) {
                    throw new IOException("Oversized Purroxy control frame");
                }
                if (frame.size() == 0) {
                    frameStarted = now;
                }
                frame.write(value);
                continue;
            }
            if (now - messageWindow >= TimeUnit.SECONDS.toNanos(1)) {
                messageWindow = now;
                messages = 0;
            }
            if (++messages > 20) {
                throw new IOException("Purroxy control rate exceeded");
            }
            JsonObject message = JsonParser.parseString(frame.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            frame.reset();
            frameStarted = 0;
            String type = message.get("type").getAsString();
            if (type.equals("error")) {
                String code = message.get("code").getAsString();
                throw new IOException(code.equals("UNKNOWN_GROUP")
                    ? "Unknown network game mode; configure the group in Purroxy before connecting this backend"
                    : "Purroxy rejected this backend's resume or control message");
            }
            if (session == null && type.equals("registered")) {
                String name = this.pairing == null ? this.config.serverId() : this.pairing.registered(message);
                this.assignedName.accept(name);
                session = UUID.fromString(message.get("session").getAsString());
                this.activeSession = session;
                if (message.get("heartbeatSeconds").getAsInt() != 5 || message.get("leaseSeconds").getAsInt() != 20) {
                    throw new IOException("Unsupported Purroxy heartbeat policy");
                }
                this.logger.info("Registered with Purroxy as " + name);
                if (this.pairing != null) {
                    try {
                        this.pairing.discardChallenge();
                    } catch (IOException failure) {
                        this.logger.warning("Remove " + this.config.challengeFile() + " by hand; it could not be deleted: "
                            + failure.getMessage());
                    }
                }
            } else if (session != null && session.equals(UUID.fromString(message.get("session").getAsString()))) {
                if (type.equals("wake")) {
                    this.wake.accept(UUID.fromString(message.get("request").getAsString()));
                } else if (type.equals("sleep") && this.sleep != null) {
                    UUID request = UUID.fromString(message.get("request").getAsString());
                    UUID requestSession = session;
                    this.sleep.apply(request).whenComplete((accepted, failure) -> {
                        if (!requestSession.equals(this.activeSession)) {
                            return;
                        }
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", "sleep-result");
                        reply.addProperty("session", requestSession.toString());
                        reply.addProperty("request", request.toString());
                        reply.addProperty("accepted", failure == null && Boolean.TRUE.equals(accepted));
                        if (!this.replies.offer(reply)) {
                            closeSocket();
                        }
                    });
                } else if (type.equals("handoff") && this.handoff != null) {
                    UUID request = UUID.fromString(message.get("request").getAsString());
                    UUID requestSession = session;
                    this.handoff.apply(message).whenComplete((result, failure) -> {
                        if (!requestSession.equals(this.activeSession)) {
                            return;
                        }
                        JsonObject reply = failure == null ? result.deepCopy() : new JsonObject();
                        reply.addProperty("type", "handoff-result");
                        reply.addProperty("session", requestSession.toString());
                        reply.addProperty("request", request.toString());
                        if (failure != null) {
                            reply.addProperty("status", "REJECTED");
                            reply.addProperty("reason", "Backend could not complete this handoff operation");
                            this.logger.warning("Handoff operation rejected: " + failure.getMessage());
                        }
                        if (!this.replies.offer(reply)) {
                            closeSocket();
                        }
                    });
                } else if (type.equals("retired")) {
                    if (this.snapshot.get().state().equals("DRAINING")) {
                        this.closed = true;
                    }
                    return;
                } else {
                    throw new IOException("Unexpected Purroxy control message");
                }
            } else {
                throw new IOException("Unexpected Purroxy control session");
            }
        }
    }

    private void send(SSLSocket socket, JsonObject message) throws IOException {
        this.writeStarted = System.nanoTime();
        try {
            socket.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } finally {
            this.writeStarted = 0;
        }
    }

    private void closeSocket() {
        Socket socket = this.activeSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a failed transport is best effort.
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.watchdog.shutdownNow();
        closeSocket();
        this.worker.interrupt();
    }
}
