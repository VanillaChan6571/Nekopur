"""Apply the native tall profile to the pinned constructor translation."""
import re


def add_profiles(source):
    def replace(before, after):
        nonlocal source
        if before not in source:
            raise ValueError('Pinned terrain translation changed: ' + before[:90])
        source = source.replace(before, after)

    replace('    private final Set<String> visiting = new HashSet<>();',
            '    private final Set<String> visiting = new HashSet<>();\n    private final boolean tall;')
    replace('WritableRegistry<NormalNoise> noises) {\n        this.functions = functions;',
            'WritableRegistry<NormalNoise> noises, boolean tall) {\n        this.tall = tall;\n        this.functions = functions;')
    replace('new TectonicTerrainFactory(functions, noises);', 'new TectonicTerrainFactory(functions, noises, false);')
    replace('    private void checkCollisions() {', '''    /** Separate density graph; shared noise keys preserve seed geography between profiles. */
    public static NoiseGeneratorSettings buildTall(WritableRegistry<DensityFunction> functions, WritableRegistry<NormalNoise> noises, HolderGetter<MaterialRule> materials) {
        TectonicTerrainFactory factory = new TectonicTerrainFactory(functions, noises, true);
        factory.checkCollisions();
        return factory.settings(materials);
    }

    private Identifier densityId(String standard) {
        return Identifier.parse(tall ? standard.replace("nekopur:tectonic/", "nekopur:tectonic_tall/") : standard);
    }

    private void checkCollisions() {''')
    source = re.sub(r'functions.containsKey\(Identifier.parse\(("nekopur:tectonic/[^"\n]+")\)\)', r'functions.containsKey(densityId(\1))', source)
    source = source.replace('if (noises.containsKey(', 'if (!tall && noises.containsKey(')
    replace('Identifier.parse("nekopur:tectonic/" + name.replace(\':\', \'/\'))',
            'densityId("nekopur:tectonic/" + name.replace(\':\', \'/\'))')
    replace('DensityFunction definition = switch (name) {', '''DensityFunction definition = switch (name) {
            case "minecraft:y" -> tall ? DensityFunctions.gradient(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE,
                -2048, 2048, -2048.0F, 2048.0F) : null;''')
    replace('return DensityFunctions.add(function("minecraft:y"), DensityFunctions.constant(64.0F));',
            'return DensityFunctions.add(function("minecraft:y"), DensityFunctions.constant(tall ? 128.0F : 64.0F));')
    # Keep sea level/coast geography, extend the bottom/top density fades, and
    # enlarge the allowed terrain offsets rather than clipping taller peaks.
    replace('-64, -48, 0.0F, 1.0F)', '(tall ? -128 : -64), (tall ? -112 : -48), 0.0F, 1.0F)')
    replace('290, 310, 1.0F, 0.0F)', '(tall ? 482 : 290), (tall ? 502 : 310), 1.0F, 0.0F)')
    replace('DensityFunctions.constant(384.0F), -64, 8)', 'DensityFunctions.constant(tall ? 512.0F : 384.0F), (tall ? -128 : -64), 8)')
    replace('return DensityFunctions.constant(-0.45F);', 'return DensityFunctions.constant(tall ? -0.95F : -0.45F);')
    replace('return DensityFunctions.constant(-0.22F);', 'return DensityFunctions.constant(tall ? -0.35F : -0.22F);')
    replace('return DensityFunctions.constant(1.125F);', 'return DensityFunctions.constant(tall ? 2.0F : 1.125F);')
    replace('DensityFunctions.constant(1.95F)),', 'DensityFunctions.constant(tall ? 3.3F : 1.95F)),')
    replace('DensityFunctions.constant(-0.6F))), function("tectonic:blend_alpha")',
            'DensityFunctions.constant(tall ? -1.2F : -0.6F))), function("tectonic:blend_alpha")')
    replace('NoiseSettings.create(-64, 384)', 'NoiseSettings.create(tall ? -128 : -64, tall ? 640 : 384)')
    # Biome climate depth is separate from terrain density. Keep ordinary cave
    # biomes above the deep layer and move Deep Dark eligibility toward the new
    # bottom, with a gradual transition rather than the original Y=32 step.
    replace('''        return DensityFunctions.min(function("tectonic:depth"), DensityFunctions.rangeChoice(function("minecraft:y"),
                     32.0F, 2048.0F, DensityFunctions.constant(0.8F), DensityFunctions.constant(1.3F)));''',
            '''        DensityFunction ceiling = tall
            ? DensityFunctions.gradient(Direction.Axis.Y, TilingMode.CLAMP_TO_EDGE, -96, -32, 1.3F, 0.8F)
            : DensityFunctions.rangeChoice(function("minecraft:y"), 32.0F, 2048.0F,
                DensityFunctions.constant(0.8F), DensityFunctions.constant(1.3F));
        return DensityFunctions.min(function("tectonic:depth"), ceiling);''')
    return source


if __name__ == '__main__':
    import pathlib
    import sys
    target = pathlib.Path(sys.argv[1])
    target.write_text(add_profiles(target.read_text(encoding='utf-8')), encoding='utf-8', newline='\n')
