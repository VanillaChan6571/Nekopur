package org.nekopur.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.NullMarked;

/** Standalone backend configuration, intentionally separate from Purpur settings. */
@NullMarked
public record NetworkConfig(boolean enabled, String proxyHost, int proxyPort, String serverId,
                            String advertisedHost, int advertisedPort, String group, String map,
                            int safeLimit, String region, boolean startSleeping, Path keyStore,
                            String passwordEnvironment, Path proxyCa, String authentication,
                            String pairingToken, Path identityFile, Path challengeFile) {
    /** Shared enrollment secret Purroxy publishes; administrators upload it into the server directory. */
    public static final String CHALLENGE_FILE = "purroxy.challenge";

    public NetworkConfig(boolean enabled, String proxyHost, int proxyPort, String serverId,
                         String advertisedHost, int advertisedPort, String group, String map,
                         int safeLimit, String region, boolean startSleeping, Path keyStore,
                         String passwordEnvironment, Path proxyCa) {
        this(enabled, proxyHost, proxyPort, serverId, advertisedHost, advertisedPort, group, map,
            safeLimit, region, startSleeping, keyStore, passwordEnvironment, proxyCa, "certificates", "",
            keyStore.toAbsolutePath().getParent().resolve("nekopurr-network/identity.json"),
            keyStore.toAbsolutePath().getParent().resolve(CHALLENGE_FILE));
    }
    public static NetworkConfig load(Path path) throws IOException, InvalidConfigurationException {
        if (!Files.exists(path)) {
            Files.writeString(path, """
                # Enable after copying purroxy.challenge from Purroxy into this directory.
                enabled: false
                purroxy:
                  ip: 127.0.0.1
                  port: 25565
                  # Optional: paste the challenge here instead of uploading the file.
                  pairing-token: ''
                server:
                  # auto uses the group: hub-1, hub-2, etc. hub-* and fixed names also work.
                  name: auto
                  # auto uses the address Purroxy sees. Override for a different Pterodactyl allocation host.
                  host: auto
                  # 0 uses Minecraft's actual listening port. Override for an externally mapped port.
                  port: 0
                resume:
                  network-gamemode: Hub
                  map: none
                  player-safe-limit: 50
                  region: US-West
                # Purroxy selects active servers and puts empty spare instances to sleep.
                transfers:
                  # Keep disabled until coordinated transfers are configured and tested.
                  enabled: false
                """);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        boolean enabled = yaml.getBoolean("enabled", false);
        String proxyHost = host(yaml, "purroxy.ip", "127.0.0.1");
        int proxyPort = port(yaml.getInt("purroxy.port", 25565), false);
        String map = yaml.getString("resume.map", "none").trim();
        if (map.isEmpty() || map.equals(".") || map.equals("..") || map.contains("/")
            || map.contains("\\") || map.contains(":")) {
            throw new IllegalArgumentException("resume.map must name a world inside the world container");
        }
        int safeLimit = yaml.getInt("resume.player-safe-limit", 50);
        if (safeLimit < 1) {
            throw new IllegalArgumentException("resume.player-safe-limit must be positive");
        }
        Path directory = path.toAbsolutePath().getParent();
        Path challengeFile = directory.resolve(yaml.getString("purroxy.challenge-file", CHALLENGE_FILE));
        String authentication = yaml.getString("purroxy.authentication", yaml.contains("tls.key-store") ? "certificates" : "pairing");
        if (!authentication.equals("pairing") && !authentication.equals("certificates")) {
            throw new IllegalArgumentException("purroxy.authentication must be pairing or certificates");
        }
        String requestedName = yaml.getString("server.name", yaml.getString("identity.server-id",
            authentication.equals("pairing") ? "auto" : "hub-1")).trim().toLowerCase(Locale.ROOT);
        if (!authentication.equals("pairing") || !requestedName.matches("[a-z0-9][a-z0-9_-]{0,47}-\\*")) {
            requestedName = identifier(requestedName);
        }
        return new NetworkConfig(enabled, proxyHost, proxyPort,
            requestedName,
            host(yaml, yaml.contains("server.host") ? "server.host" : "identity.advertised-host",
                authentication.equals("pairing") ? "auto" : "127.0.0.1"),
            port(yaml.getInt("server.port", yaml.getInt("identity.advertised-port", 0)), true),
            identifier(yaml.getString("resume.network-gamemode", "Hub")), map, safeLimit,
            identifier(yaml.getString("resume.region", "US-West")),
            authentication.equals("pairing") || yaml.getBoolean("sleep.start-sleeping", false),
            directory.resolve(yaml.getString("tls.key-store", "nekopurr-backend.p12")),
            yaml.getString("tls.password-environment", "NEKOPURR_KEYSTORE_PASSWORD"),
            directory.resolve(yaml.getString("tls.proxy-ca", "purroxy-ca.crt")), authentication,
            challenge(yaml, challengeFile), directory.resolve("nekopurr-network/identity.json"), challengeFile);
    }

    /** Prefers an uploaded challenge file so administrators never edit the YAML to enroll a backend. */
    private static String challenge(YamlConfiguration yaml, Path challengeFile) throws IOException {
        String configured = yaml.getString("purroxy.pairing-token", "").trim();
        if (!configured.isEmpty() || !Files.isRegularFile(challengeFile)) {
            return configured;
        }
        if (Files.size(challengeFile) > 4096) {
            throw new IOException("Oversized " + CHALLENGE_FILE);
        }
        return Files.readString(challengeFile).trim();
    }

    private static String identifier(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid network identifier: " + value);
        }
        return normalized;
    }

    private static String host(YamlConfiguration yaml, String key, String fallback) {
        String value = yaml.getString(key, fallback).trim();
        if (value.isEmpty() || value.equals("0.0.0.0") || value.equals("::") || value.equals("[::]")) {
            throw new IllegalArgumentException(key + " must be a reachable host, not a wildcard bind address");
        }
        return value;
    }

    private static int port(int value, boolean allowAutomatic) {
        if (value < (allowAutomatic ? 0 : 1) || value > 65535) {
            throw new IllegalArgumentException("Invalid network port: " + value);
        }
        return value;
    }
}
