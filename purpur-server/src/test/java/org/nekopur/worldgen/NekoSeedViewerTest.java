package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.nekopur.worldgen.preview.NekoSeedPreview;
import org.nekopur.worldgen.preview.NekoSeedViewer;

@VanillaFeature
class NekoSeedViewerTest {
    private static HttpResponse<String> post(HttpClient client, NekoSeedViewer viewer, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(viewer.url() + "/api/preview"))
            .header("Content-Type", "application/json").header("Origin", viewer.url())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void liveMapsUseNativeSamplerAndPreserveLongSeeds() throws Exception {
        var registries = RegistryHelper.registryAccess();
        try (NekoSeedViewer viewer = new NekoSeedViewer(registries, 0, 4); HttpClient client = HttpClient.newHttpClient()) {
            viewer.start();
            HttpResponse<String> info = client.send(HttpRequest.newBuilder(URI.create(viewer.url() + "/api/info")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, info.statusCode());
            assertEquals("nekopur:neko", JsonParser.parseString(info.body()).getAsJsonObject().get("preset").getAsString());
            JsonObject defaults = JsonParser.parseString(info.body()).getAsJsonObject();
            assertEquals("6571", defaults.get("initialSeed").getAsString());
            assertEquals(0, defaults.get("initialX").getAsInt());
            assertEquals(0, defaults.get("initialZ").getAsInt());
            assertEquals("fast", defaults.get("initialMode").getAsString());
            String body = "{\"seed\":\"9223372036854775807\",\"size\":64,\"step\":128,\"x\":-17,\"z\":3,\"mode\":\"fast\"}";
            HttpResponse<String> response = post(client, viewer, body);
            assertEquals(200, response.statusCode(), response.body());
            JsonObject frame = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(Long.toString(Long.MAX_VALUE), frame.get("seed").getAsString());
            assertEquals(-4113, frame.get("minX").getAsInt());
            assertEquals(-4093, frame.get("minZ").getAsInt());
            assertEquals(4096, frame.getAsJsonArray("elevation").size());
            assertEquals(frame.getAsJsonArray("biomeNames").size(), frame.getAsJsonArray("biomeColors").size());
            assertTrue(frame.getAsJsonObject("images").get("terrain").getAsString().startsWith("data:image/png;base64,"));
            NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(NekoWorldgenBootstrap.PRESET).value().overworld().orElseThrow().generator();
            RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), Long.MAX_VALUE, generator.generatorSettings().value());
            for (int index : new int[]{0, 33, 2048, 4095}) {
                NekoSeedPreview.Sample point = NekoSeedPreview.sample(registries, generator, random, SamplerContext.builder().enableCaches().build(), -4113 + index % 64 * 128, -4093 + index / 64 * 128, false);
                assertEquals(point.elevation(), frame.getAsJsonArray("elevation").get(index).getAsInt());
                int biome = frame.getAsJsonArray("biomes").get(index).getAsInt();
                assertEquals(point.biome(), frame.getAsJsonArray("biomeNames").get(biome).getAsString());
            }
            assertEquals(response.body(), post(client, viewer, body).body(), "cached frame must be identical");
            JsonObject moved = JsonParser.parseString(post(client, viewer, body.replace("\"x\":-17", "\"x\":8192")).body()).getAsJsonObject();
            assertNotEquals(frame.get("elevation"), moved.get("elevation"));
            JsonObject textSeed = JsonParser.parseString(post(client, viewer, body.replace("9223372036854775807", "neko")).body()).getAsJsonObject();
            assertEquals(Integer.toString("neko".hashCode()), textSeed.get("seed").getAsString());
            assertNotEquals(frame.get("elevation"), textSeed.get("elevation"));
            JsonObject tall = JsonParser.parseString(post(client, viewer, body.replace("\"seed\":", "\"preset\":\"neko-mmo-tall\",\"seed\":")).body()).getAsJsonObject();
            assertEquals("nekopur:neko-mmo-tall", tall.get("preset").getAsString());
            assertNotEquals(frame.get("elevation"), tall.get("elevation"));
            assertEquals(response.body(), post(client, viewer, body).body(), "A tall request must not contaminate the standard seed state");
            assertEquals(400, post(client, viewer, body.replace("\"seed\":", "\"preset\":\"unknown\",\"seed\":")).statusCode());
        }
    }

    @Test void rejectsUnsafeAndUnboundedRequests() throws Exception {
        try (NekoSeedViewer viewer = new NekoSeedViewer(RegistryHelper.registryAccess(), 0, 1); HttpClient client = HttpClient.newHttpClient()) {
            viewer.start();
            for (String body : Arrays.asList("{}", "null", "[]", "{", "{\"seed\":12}", "{\"seed\":null}", "{\"seed\":\"1\",\"mode\":null}", "{\"seed\":\"1\",\"mode\":{}}", "{\"seed\":\" \"}", "{\"seed\":\"1\",\"size\":257}", "{\"seed\":\"1\",\"step\":0}", "{\"seed\":\"1\",\"x\":30000000}", "{\"seed\":\"1\",\"x\":1.5}", "{\"seed\":\"1\",\"size\":256,\"mode\":\"detailed\"}")) {
                assertEquals(400, post(client, viewer, body).statusCode(), body);
            }
            assertEquals(413, post(client, viewer, " ".repeat(16385)).statusCode());
            HttpResponse<String> foreign = client.send(HttpRequest.newBuilder(URI.create(viewer.url() + "/api/preview")).header("Origin", "https://example.com").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"seed\":\"1\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, foreign.statusCode());
            assertFalse(foreign.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            assertEquals(415, client.send(HttpRequest.newBuilder(URI.create(viewer.url() + "/api/preview")).header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(viewer.url() + "/api/preview")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
