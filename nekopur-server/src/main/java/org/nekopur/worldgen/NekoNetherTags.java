package org.nekopur.worldgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.WritableRegistry;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.NullMarked;

/** Adds native Nether membership to already-expanded biome tags before registry freezing. */
@NullMarked
public final class NekoNetherTags {
    private NekoNetherTags() {
    }

    public static void register(WritableRegistry<Biome> biomes) {
        Holder<Biome> ashen = biomes.getOrThrow(NekoNetherBiomes.ASHEN_BARRENS);
        Holder<Biome> ember = biomes.getOrThrow(NekoNetherBiomes.EMBER_GROVE);
        Holder<Biome> soulwood = biomes.getOrThrow(NekoNetherBiomes.SOULWOOD_HOLLOW);
        Holder<Biome> obsidian = biomes.getOrThrow(NekoNetherBiomes.OBSIDIAN_SHORES);
        List<Holder<Biome>> all = List.of(ashen, ember, soulwood, obsidian);
        Map<TagKey<Biome>, List<Holder<Biome>>> merged = new LinkedHashMap<>();
        append(biomes, merged, BiomeTags.IS_NETHER, all);
        append(biomes, merged, BiomeTags.HAS_NETHER_FORTRESS, all);
        append(biomes, merged, BiomeTags.HAS_RUINED_PORTAL_NETHER, all);
        append(biomes, merged, BiomeTags.HAS_BASTION_REMNANT, List.of(ember, soulwood, obsidian));
        append(biomes, merged, BiomeTags.HAS_NETHER_FOSSIL, List.of(ashen, soulwood));
        for (TagKey<Biome> tag : List.of(BiomeTags.SPAWNS_WARM_VARIANT_FROGS, BiomeTags.SPAWNS_WARM_VARIANT_FARM_ANIMALS)) {
            HolderSet.Named<Biome> contents = biomes.createRegistrationLookup().getOrThrow(tag);
            if (contents.isBound() && contents.stream().anyMatch(NekoNetherTags::isVanillaNether)) {
                append(biomes, merged, tag, all);
            } else if (!contents.isBound()) {
                // Registration lookup creates missing named sets; keep them valid without adding membership.
                merged.put(tag, List.of());
            }
        }
        biomes.bindTags(merged);
    }

    private static void append(WritableRegistry<Biome> biomes, Map<TagKey<Biome>, List<Holder<Biome>>> merged,
                               TagKey<Biome> tag, List<Holder<Biome>> nativeBiomes) {
        HolderSet.Named<Biome> contents = biomes.createRegistrationLookup().getOrThrow(tag);
        List<Holder<Biome>> values = new ArrayList<>(contents.isBound() ? contents.stream().toList() : List.of());
        for (Holder<Biome> nativeBiome : nativeBiomes) {
            if (!values.contains(nativeBiome)) values.add(nativeBiome);
        }
        merged.put(tag, List.copyOf(values));
    }

    private static boolean isVanillaNether(Holder<Biome> biome) {
        return biome.is(Biomes.NETHER_WASTES) || biome.is(Biomes.SOUL_SAND_VALLEY) || biome.is(Biomes.CRIMSON_FOREST)
            || biome.is(Biomes.WARPED_FOREST) || biome.is(Biomes.BASALT_DELTAS);
    }
}
