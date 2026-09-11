package org.nekopur.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Persists the backend instance and verifies the proxy certificate pinned in its enrollment challenge. */
@NullMarked
final class BackendPairing {
    // entityIdBase is assigned by Purroxy, which is the authority for id ranges as it is for names.
    // Persisted so a restart applies it at startup rather than waiting for the registration reply.
    private record Identity(UUID instance, String pin, String token, String credential, String name,
                            int entityIdBase) {
        Identity(UUID instance, String pin, String token, String credential, String name) {
            this(instance, pin, token, credential, name, 0);
        }
    }

    private static final Gson GSON = new Gson();
    private final Path file;
    private final @Nullable Path challengeFile;
    private Identity identity;

    BackendPairing(Path file, String token) throws IOException {
        this(file, token, null);
    }

    BackendPairing(Path file, String token, @Nullable Path challengeFile) throws IOException {
        this.file = file;
        this.challengeFile = challengeFile;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path directory = file.toAbsolutePath().getParent();
        if (Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        if (Files.exists(file)) {
            if (Files.size(file) > 4096) {
                throw new IOException("Oversized backend identity file");
            }
            try {
                this.identity = GSON.fromJson(Files.readString(file), Identity.class);
                if (this.identity == null || this.identity.instance() == null || !this.identity.pin().matches("[a-f0-9]{64}")
                    || !(secret(this.identity.token()) && this.identity.credential().isEmpty() && this.identity.name().isEmpty()
                        || this.identity.token().isEmpty() && secret(this.identity.credential())
                            && this.identity.name().matches("[a-z0-9][a-z0-9_-]{0,63}"))) {
                    throw new IllegalArgumentException();
                }
                if (this.identity.credential().isEmpty() && !token.isEmpty()) {
                    String[] replacement = parseToken(token);
                    Identity pending = new Identity(this.identity.instance(), replacement[1], replacement[2], "", "");
                    if (!pending.equals(this.identity)) {
                        save(pending);
                        this.identity = pending;
                    }
                }
            } catch (RuntimeException failure) {
                throw new IOException("Invalid backend identity; refusing to replace existing credentials", failure);
            }
        } else {
            String[] parts = parseToken(token);
            this.identity = new Identity(UUID.randomUUID(), parts[1], parts[2], "", "");
            save(this.identity); // Preserve the instance even if the first enrollment reply is lost.
        }
    }

    private static String[] parseToken(String token) throws IOException {
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !parts[0].equals("p1") || !parts[1].matches("[a-f0-9]{64}") || !secret(parts[2])) {
            throw new IOException("Upload purroxy-pairing/purroxy.challenge from Purroxy into this server directory before enabling");
        }
        return parts;
    }

    synchronized JsonObject authentication() {
        JsonObject message = new JsonObject();
        message.addProperty("instance", this.identity.instance().toString());
        if (this.identity.credential().isEmpty()) {
            message.addProperty("token", this.identity.token());
        } else {
            message.addProperty("credential", this.identity.credential());
        }
        return message;
    }

    synchronized String registered(JsonObject message) throws IOException {
        String name = message.get("serverId").getAsString();
        String credential = message.get("credential").getAsString();
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,63}") || !secret(credential)
            || !this.identity.name().isEmpty() && !this.identity.name().equals(name)) {
            throw new IOException("Purroxy returned an invalid or changed permanent server identity");
        }
        int entityIdBase = message.has("entityIdBase") ? message.get("entityIdBase").getAsInt() : 0;
        if (entityIdBase < 0) {
            throw new IOException("Purroxy returned an invalid entity id base");
        }
        Identity next = new Identity(this.identity.instance(), this.identity.pin(), "", credential, name,
            entityIdBase);
        if (!next.equals(this.identity)) {
            save(next); // Persist before the first heartbeat confirms enrollment to Purroxy.
            this.identity = next;
        }
        return name;
    }

    SSLContext tls() throws Exception {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        byte[] expected = HexFormat.of().parseHex(this.identity.pin());
        X509ExtendedTrustManager trust = new X509ExtendedTrustManager() {
            private void verify(X509Certificate[] chain) throws CertificateException {
                if (chain.length == 0) {
                    throw new CertificateException("Proxy certificate is missing");
                }
                chain[0].checkValidity();
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(expected, actual)) {
                        throw new CertificateException("Proxy certificate does not match the pairing token");
                    }
                } catch (java.security.NoSuchAlgorithmException impossible) {
                    throw new AssertionError(impossible);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                verify(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Backend pairing does not authenticate TLS clients");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        context.init(null, new javax.net.ssl.TrustManager[]{trust}, null);
        return context;
    }

    /** The entity id range Purroxy assigned this backend, or 0 before one has been assigned. */
    synchronized int entityIdBase() {
        return this.identity.entityIdBase();
    }

    /** Removes the shared challenge once this backend holds its own credential. */
    synchronized void discardChallenge() throws IOException {
        if (this.challengeFile != null && !this.identity.credential().isEmpty()) {
            Files.deleteIfExists(this.challengeFile);
        }
    }

    private static boolean secret(String value) {
        return value.matches("[a-zA-Z0-9_-]{43}");
    }

    private void save(Identity next) throws IOException {
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(next));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
