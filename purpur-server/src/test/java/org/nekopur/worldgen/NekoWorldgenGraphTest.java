package org.nekopur.worldgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

/** Checks transitive references, including vanilla definitions reused by the native graph. */
@VanillaFeature
@NullMarked
public class NekoWorldgenGraphTest {
    @Test
    void surfaceMaterialsUseNativePreliminaryHeight() {
        Set<String> references = new HashSet<>();
        RegistryOps<JsonElement> ops = RegistryHelper.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        NoiseGeneratorSettings settings = RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NekoWorldgenBootstrap.NOISE_SETTINGS).value();
        collectReferences(DensityFunction.CODEC.encodeStart(ops, settings.noiseRouter().chunkSurfaceLevel()).getOrThrow(), ops, references);
        assertTrue(references.contains("nekopur:tectonic/minecraft/overworld/preliminary_surface_level"),
            "Surface rules must estimate Tectonic terrain heights instead of vanilla heights");
    }

    @Test
    void reachableDependenciesNeverUseAnOverriddenVanillaDefinition() {
        Set<String> references = new HashSet<>();
        RegistryOps<JsonElement> ops = RegistryHelper.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        NoiseGeneratorSettings settings = RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NekoWorldgenBootstrap.NOISE_SETTINGS).value();
        collectReferences(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow(), ops, references);
        for (String reference : references) {
            if (reference.startsWith("minecraft:")) {
                Identifier replacement = Identifier.parse("nekopur:tectonic/" + reference.replace(':', '/'));
                boolean overridden = RegistryHelper.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).containsKey(replacement)
                    || RegistryHelper.registryAccess().lookupOrThrow(Registries.NOISE).containsKey(replacement);
                assertFalse(overridden, "Native terrain still reaches replaced vanilla dependency: " + reference);
            }
        }
    }

    private static void collectReferences(JsonElement node, RegistryOps<JsonElement> ops, Set<String> references) {
        if (node.isJsonObject()) {
            node.getAsJsonObject().entrySet().forEach(entry -> collectReferences(entry.getValue(), ops, references));
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(element -> collectReferences(element, ops, references));
        } else if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            String reference = node.getAsString();
            Identifier id = Identifier.tryParse(reference);
            if (id == null || !references.add(reference)) {
                return;
            }
            Registry<DensityFunction> functions = RegistryHelper.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
            if (functions.containsKey(id)) {
                collectReferences(DensityFunction.CODEC.encodeStart(ops, functions.getValue(id)).getOrThrow(), ops, references);
            }
        }
    }
}
