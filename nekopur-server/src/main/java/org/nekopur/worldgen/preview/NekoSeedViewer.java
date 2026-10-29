package org.nekopur.worldgen.preview;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.NullMarked;
import org.nekopur.worldgen.NekoWorldgenBootstrap;

/** Loopback-only native seed sampler. No Minecraft world is opened or written. */
@NullMarked
public final class NekoSeedViewer implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private final RegistryAccess registries;
    private final HttpServer server;
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService workers;
    private final int threads;
    private final Semaphore sampling = new Semaphore(1);
    private String initialSeed = "6571";
    private int initialX;
    private int initialZ;
    private String initialMode = "fast";
    // All accesses are guarded by sampling, so cached states are never used by two frames at once.
    private record SeedState(String preset, long seed) {}
    private final Map<SeedState, RandomState> states = boundedCache(4);
    private final Map<Frame, byte[]> frames = boundedCache(8);

    private record Frame(long seed, int size, int step, int x, int z, boolean detailed, String preset) {}
    private String initialPreset = "nekopur:neko";

    private static <K, V> Map<K, V> boundedCache(int limit) {
        return new LinkedHashMap<>(limit + 1, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> entry) { return size() > limit; }
        };
    }

    /** Inject an already loaded production registry set, including in native integration tests. */
    public NekoSeedViewer(RegistryAccess registries, int port, int threads) throws IOException {
        if (port < 0 || port > 65535 || threads < 1 || threads > 32) throw new IllegalArgumentException("port must be 0..65535 and threads 1..32");
        this.registries = registries;
        this.threads = threads;
        this.workers = Executors.newFixedThreadPool(threads);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setExecutor(requests);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (!("127.0.0.1:" + port()).equals(host) || (origin != null && !url().equals(origin))) {
                error(exchange, 403, "Only the viewer's loopback origin is allowed"); return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/preview")) {
                if (!exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "Use POST"); return; }
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                    error(exchange, 415, "Send application/json"); return;
                }
                if (!sampling.tryAcquire()) { error(exchange, 429, "The native sampler is busy; retry when the current map finishes"); return; }
                try {
                    byte[] body = exchange.getRequestBody().readNBytes(16 * 1024 + 1);
                    if (body.length > 16 * 1024) { error(exchange, 413, "Request exceeds 16 KiB"); return; }
                    Frame frame = parseFrame(new String(body, StandardCharsets.UTF_8));
                    byte[] response = frames.get(frame);
                    if (response == null) {
                        response = render(frame);
                        frames.put(frame, response);
                    }
                    send(exchange, 200, "application/json; charset=utf-8", response);
                } catch (IllegalArgumentException exception) {
                    error(exchange, 400, exception.getMessage() == null ? "Invalid preview options" : exception.getMessage());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); error(exchange, 503, "Viewer is shutting down");
                } catch (java.util.concurrent.ExecutionException exception) {
                    System.err.println("Native seed preview failed: " + exception.getCause());
                    error(exchange, 500, "Native terrain sampling failed");
                } finally { sampling.release(); }
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "Use GET"); return; }
            if (path.equals("/api/info")) {
                send(exchange, 200, "application/json; charset=utf-8", JSON.toJson(Map.of("preset", "nekopur:neko", "version", "26.3", "ready", true,
                    "initialSeed", initialSeed, "initialX", initialX, "initialZ", initialZ, "initialMode", initialMode,
                    "initialPreset", initialPreset)).getBytes(StandardCharsets.UTF_8)); return;
            }
            String resource = switch (path) {
                case "/", "/index.html" -> "viewer.html";
                case "/viewer.js" -> "viewer.js";
                case "/viewer-math.js" -> "viewer-math.js";
                default -> "";
            };
            if (resource.isEmpty()) { error(exchange, 404, "Not found"); return; }
            try (java.io.InputStream input = NekoSeedViewer.class.getResourceAsStream("/org/nekopur/worldgen/" + resource)) {
                if (input == null) { error(exchange, 404, "Viewer resource is missing"); return; }
                send(exchange, 200, resource.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8", input.readAllBytes());
            }
        } catch (IOException exception) {
            // A browser can cancel a request while navigating. The bounded frame finishes and can be reused.
            exchange.close();
        }
    }

    private static Frame parseFrame(String body) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (!object.has("seed") || !object.get("seed").isJsonPrimitive() || !object.get("seed").getAsJsonPrimitive().isString()) throw new IllegalArgumentException("seed must be a string to preserve 64-bit precision");
            String input = object.get("seed").getAsString();
            if (input.length() > 1024) throw new IllegalArgumentException("Seed text is too long");
            long seed = WorldOptions.parseSeed(input).orElseThrow(() -> new IllegalArgumentException("Specify a nonempty seed"));
            int size = integer(object, "size", 256);
            int step = integer(object, "step", 128);
            int x = integer(object, "x", 0), z = integer(object, "z", 0);
            String mode = object.has("mode") ? object.get("mode").getAsString() : "fast";
            if (!mode.equals("fast") && !mode.equals("detailed")) throw new IllegalArgumentException("mode must be fast or detailed");
            boolean detailed = mode.equals("detailed");
            if (size < 64 || size > (detailed ? 128 : 256)) throw new IllegalArgumentException("size must be 64..256 in fast mode or 64..128 in detailed mode");
            String preset = NekoSeedPreview.normalizePreset(object.has("preset") ? object.get("preset").getAsString() : "neko");
            new NekoSeedPreview.Options(seed, size, step, x, z, 1, detailed, Path.of("."), preset);
            return new Frame(seed, size, step, x, z, detailed, preset);
        } catch (com.google.gson.JsonParseException | IllegalStateException | UnsupportedOperationException | NullPointerException exception) {
            throw new IllegalArgumentException("Malformed preview JSON", exception);
        }
    }

    private static int integer(JsonObject object, String name, int fallback) {
        if (!object.has(name)) return fallback;
        try { return new java.math.BigDecimal(object.get(name).getAsString()).intValueExact(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(name + " must be an integer", exception); }
    }

    private byte[] render(Frame frame) throws InterruptedException, java.util.concurrent.ExecutionException, IOException {
        NoiseBasedChunkGenerator generator = NekoSeedPreview.generator(registries, frame.preset());
        RandomState random = states.computeIfAbsent(new SeedState(frame.preset(), frame.seed()), key -> RandomState.create(registries.lookupOrThrow(Registries.NOISE), key.seed(), generator.generatorSettings().value()));
        NekoSeedPreview.Options options = new NekoSeedPreview.Options(frame.seed(), frame.size(), frame.step(), frame.x(), frame.z(), threads, frame.detailed(), Path.of("."), frame.preset());
        int[] elevations = new int[frame.size() * frame.size()];
        String[] biomes = new String[elevations.length];
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < threads; worker++) {
                int firstRow = worker;
                tasks.add(workers.submit(() -> {
                    SamplerContext context = SamplerContext.builder().enableCaches().build();
                    for (int row = firstRow; row < frame.size(); row += threads) {
                        if (Thread.currentThread().isInterrupted()) return;
                        for (int column = 0; column < frame.size(); column++) {
                            if (Thread.currentThread().isInterrupted()) return;
                            NekoSeedPreview.Sample point = NekoSeedPreview.sample(registries, generator, random, context, options.blockX(column), options.blockZ(row), frame.detailed());
                            int index = row * frame.size() + column;
                            elevations[index] = point.elevation(); biomes[index] = point.biome();
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) task.get();
            return JSON.toJson(NekoSeedPreview.previewData(options, elevations, biomes, generator.generatorSettings().value().seaLevel())).getBytes(StandardCharsets.UTF_8);
        } finally { for (Future<?> task : tasks) if (!task.isDone()) task.cancel(true); }
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.toJson(Map.of("error", message)).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        server.stop(0); requests.shutdownNow(); workers.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = 8765, threads = 4;
        String seed = "6571", mode = "fast", preset = "neko";
        int x = 0, z = 0;
        for (int i = 0; i < args.length; i += 2) {
            if (args[i].equals("--help")) { System.out.println("NekoSeedViewer --port <1..65535> --threads <1..32> --seed <number/text> --x <center> --z <center> --mode <fast/detailed>"); return; }
            if (i + 1 == args.length) throw new IllegalArgumentException("Use --option value pairs");
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = args[i + 1];
                case "--x" -> x = Integer.parseInt(args[i + 1]);
                case "--z" -> z = Integer.parseInt(args[i + 1]);
                case "--mode" -> mode = args[i + 1];
                case "--preset" -> preset = NekoSeedPreview.normalizePreset(args[i + 1]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        // Validate the initial view through the same request rules before loading native registries.
        parseFrame(JSON.toJson(Map.of("seed", seed, "x", x, "z", z, "mode", mode, "preset", preset, "size", mode.equals("detailed") ? 128 : 256)));
        System.out.println("Loading native Neko registries...");
        NekoSeedViewer viewer = new NekoSeedViewer(NekoSeedPreview.loadRegistries(), port, threads);
        viewer.initialSeed = seed;
        viewer.initialX = x;
        viewer.initialZ = z;
        viewer.initialMode = mode;
        viewer.initialPreset = NekoSeedPreview.normalizePreset(preset);
        Runtime.getRuntime().addShutdownHook(new Thread(viewer::close, "neko-seed-viewer-shutdown"));
        viewer.start();
        System.out.println("Neko seed viewer ready: " + viewer.url());
    }
}
