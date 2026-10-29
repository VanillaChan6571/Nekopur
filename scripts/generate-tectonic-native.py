"""Offline conversion of pinned Tectonic 3.0.29 into native Java constructors.

Input is the upstream release ZIP; no ZIP/JSON resources are loaded at runtime.
"""
import hashlib
import json
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit('Usage: python scripts/generate-tectonic-native.py <tectonic-3.0.29-datapack.zip>')
ARCHIVE = pathlib.Path(sys.argv[1])
EXPECTED = '529d7faf049c982272a5dfa025085baf02492b84'
if hashlib.sha1(ARCHIVE.read_bytes()).hexdigest() != EXPECTED:
    raise SystemExit('Unexpected upstream archive: use the pinned Tectonic 3.0.29 release')
archive = zipfile.ZipFile(ARCHIVE)
def registry(kind):
    result = {}
    for name in sorted(archive.namelist()):
        parts = name.split('/')
        if len(parts) > 4 and parts[0] == 'data' and parts[2:4] == ['worldgen', kind] and name.endswith('.json'):
            result[parts[1] + ':' + '/'.join(parts[4:])[:-5]] = json.loads(archive.read(name))
    return result
functions = registry('density_function')
noises = registry('noise')
settings = registry('noise_settings')['minecraft:overworld']
def q(s): return json.dumps(s)
def f(x): return repr(float(x)) + 'F'
def renamed(s): return 'nekopur:tectonic/' + s.replace(':', '/')
def spline(node):
    if isinstance(node, (int, float)): return 'CubicSpline.constant(' + f(node) + ')'
    pts = node['points']
    return ('new CubicSpline.Multipoint<>(new SplineFunction.Coordinate(' + expr(node['coordinate']) + '), new float[]{' + ', '.join(f(p['location']) for p in pts) + '}, List.of(' + ', '.join(spline(p['value']) for p in pts) + '), new float[]{' + ', '.join(f(p['derivative']) for p in pts) + '})')
def expr(node):
    if isinstance(node, (int, float)): return 'DensityFunctions.constant(' + f(node) + ')'
    if isinstance(node, str): return 'function(' + q(node) + ')'
    typ = node['type'].split(':')[1]
    unary = {'abs':'abs','square':'square','cube':'cube','half_negative':'halfNegative','quarter_negative':'quarterNegative','reciprocal':'reciprocal','negate':'negate','squeeze':'squeeze','cache':'cache','blend_density':'blendDensity'}
    if typ in unary: return 'DensityFunctions.' + unary[typ] + '(' + expr(node['input']) + ')'
    if typ in ['add','sub','mul','min','max']: return 'DensityFunctions.' + typ + '(' + expr(node['left']) + ', ' + expr(node['right']) + ')'
    if typ in ['beardifier','blend_alpha','blend_offset']: return 'DensityFunctions.' + {'beardifier':'beardifier','blend_alpha':'blendAlpha','blend_offset':'blendOffset'}[typ] + '()'
    if typ == 'clamp': return 'DensityFunctions.clamp(' + expr(node['input']) + ', ' + f(node['min']) + ', ' + f(node['max']) + ')'
    if typ == 'noise': return 'new NoiseFunction(noise(' + q(node['noise']) + '), ' + str(float(node['xz_scale'])) + ', ' + str(float(node['y_scale'])) + ', ' + ', '.join(expr(node.get('shift_'+a, 0)) for a in ['x','y','z']) + ')'
    if typ == 'spline': return 'DensityFunctions.spline(' + spline(node['spline']) + ')'
    if typ == 'interpolated': return 'DensityFunctions.interpolated(' + expr(node['input']) + ', ' + str(node['cell_size_xz']) + ', ' + str(node['cell_size_y']) + ')'
    if typ == 'range_choice': return 'DensityFunctions.rangeChoice(' + expr(node['input']) + ', ' + f(node['min_inclusive']) + ', ' + f(node['max_exclusive']) + ', ' + expr(node['when_in_range']) + ', ' + expr(node['when_out_of_range']) + ')'
    if typ == 'find_top_surface': return 'DensityFunctions.findTopSurface(' + expr(node['density']) + ', ' + expr(node['upper_bound']) + ', ' + str(node['lower_bound']) + ', ' + str(node['cell_height']) + ')'
    if typ == 'gradient': return 'DensityFunctions.gradient(Direction.Axis.' + node['axis'].upper() + ', TilingMode.' + {'clamp':'CLAMP_TO_EDGE', 'clamp_to_edge':'CLAMP_TO_EDGE','repeat':'REPEAT','mirror':'MIRROR'}[node.get('tiling','clamp_to_edge')] + ', ' + str(node['from_coordinate']) + ', ' + str(node['to_coordinate']) + ', ' + f(node['from_value']) + ', ' + f(node['to_value']) + ')'
    if typ == 'lerp': return 'DensityFunctions.lerp(' + expr(node['alpha']) + ', ' + expr(node['first']) + ', ' + expr(node['second']) + ')'
    raise ValueError(node)
lines = ['package org.nekopur.worldgen;', '', 'import java.util.*;', 'import net.minecraft.core.*;', 'import net.minecraft.core.registries.Registries;', 'import net.minecraft.resources.*;', 'import net.minecraft.util.CubicSpline;', 'import net.minecraft.world.level.biome.Climate;', 'import net.minecraft.world.level.block.Blocks;', 'import net.minecraft.world.level.levelgen.*;', 'import net.minecraft.world.level.levelgen.densityfunction.*;', 'import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;', 'import net.minecraft.world.level.levelgen.densityfunction.op.SplineFunction;', 'import net.minecraft.world.level.levelgen.material.rule.MaterialRule;', 'import net.minecraft.world.level.levelgen.synth.NormalNoise;', 'import org.jspecify.annotations.NullMarked;', '', '/** Native constructor translation of Apollo\'s Tectonic 3.0.29 for Minecraft 26.3.', ' * Generated by scripts/generate-tectonic-native.py. No runtime datapack or JSON.', ' * Release: https://modrinth.com/datapack/tectonic/version/3.0.29-datapack', ' * Upstream artifact SHA-1: ' + EXPECTED, ' */', '@NullMarked', 'public final class TectonicTerrainFactory {', '    private final WritableRegistry<DensityFunction> functions;', '    private final WritableRegistry<NormalNoise> noises;', '    private final Map<String, Holder<DensityFunction>> built = new HashMap<>();', '    private final Set<String> visiting = new HashSet<>();', '', '    private TectonicTerrainFactory(WritableRegistry<DensityFunction> functions, WritableRegistry<NormalNoise> noises) {', '        this.functions = functions;', '        this.noises = noises;', '    }', '', '    public static NoiseGeneratorSettings build(WritableRegistry<DensityFunction> functions, WritableRegistry<NormalNoise> noises, HolderGetter<MaterialRule> materials) {', '        TectonicTerrainFactory factory = new TectonicTerrainFactory(functions, noises);', '        factory.checkCollisions();', '        factory.registerNoises();', '        return factory.settings(materials);', '    }', '', '    private void checkCollisions() {']
for key in functions: lines += ['        if (functions.containsKey(Identifier.parse(' + q(renamed(key)) + '))) throw new IllegalStateException("Reserved Neko density key already exists: ' + renamed(key) + '");']
for key in noises: lines += ['        if (noises.containsKey(Identifier.parse(' + q(renamed(key)) + '))) throw new IllegalStateException("Reserved Neko noise key already exists: ' + renamed(key) + '");']
lines += ['    }', '', '    private void registerNoises() {']
for key,n in noises.items():
    builder = 'NormalNoise.builder().setBaseAmplitude(' + str(float(n.get('base_amplitude',1))) + ').setBaseOctave(' + str(n['base_octave']) + ').setOctaveCount(' + str(n['octave_count']) + ')'
    norm = n.get('normalize',True)
    builder += '.setLegacyNormalization()' if norm == 'legacy' else '.setNormalize(' + str(norm).lower() + ')'
    for i,a in enumerate(n.get('amplitude_modifiers', [])): builder += '.setAmplitudeModifier(' + str(i) + ', ' + str(float(a)) + ')'
    lines += ['        noises.register(ResourceKey.create(Registries.NOISE, Identifier.parse(' + q(renamed(key)) + ')), ' + builder + '.build(), RegistrationInfo.BUILT_IN);']
lines += ['    }', '', '    private Holder<NormalNoise> noise(String name) {', '        String mapped = switch (name) {']
for key in noises: lines += ['            case ' + q(key) + ' -> ' + q(renamed(key)) + ';']
lines += ['            default -> name;', '        };', '        return noises.getOrThrow(ResourceKey.create(Registries.NOISE, Identifier.parse(mapped)));', '    }', '', '    private DensityFunction function(String name) {', '        return new DensityFunctions.HolderHolder(holder(name));', '    }', '', '    private Holder<DensityFunction> holder(String name) {', '        Holder<DensityFunction> existing = built.get(name);', '        if (existing != null) return existing;', '        if (!visiting.add(name)) throw new IllegalStateException("Cyclic native terrain dependency: " + name);', '        DensityFunction definition = switch (name) {']
for i,key in enumerate(functions): lines += ['            case ' + q(key) + ' -> definition' + str(i) + '();']
lines += ['            default -> null;', '        };', '        Holder<DensityFunction> result = definition == null ? functions.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(name))) : functions.register(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("nekopur:tectonic/" + name.replace(\':\', \'/\'))), definition, RegistrationInfo.BUILT_IN);', '        visiting.remove(name);', '        built.put(name, result);', '        return result;', '    }']
for i,(key,node) in enumerate(functions.items()):
    value = 'DensityFunctions.constant(NekoGeography.oceanOffset())' if key == 'tectonic:__constants/ocean_offset' else expr(node)
    lines += ['', '    // ' + key, '    private DensityFunction definition' + str(i) + '() {', '        return ' + value + ';', '    }']
lines += ['', '    private NoiseGeneratorSettings settings(HolderGetter<MaterialRule> materials) {']
for key in functions: lines += ['        holder(' + q(key) + ');']
router = settings['noise_router']; aquifers = settings['aquifers']
targets = []
for target in settings['spawn_target']:
    targets.append('new SpawnTargetPoint(Map.of(' + ', '.join('holder(' + q(k) + '), Climate.Parameter.span(' + f(v[0]) + ', ' + f(v[1]) + ')' for k,v in target.items()) + '))')
lines += ['        return new NoiseGeneratorSettings(NoiseSettings.create(' + str(settings['noise']['min_y']) + ', ' + str(settings['noise']['height']) + '), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(),', '            new NoiseRouter(' + ', '.join(expr(router[k]) for k in ['temperature','vegetation','continents','erosion','depth','ridges','chunk_surface_level','final_density']) + '),', '            materials.getOrThrow(ResourceKey.create(Registries.MATERIAL_RULE, Identifier.parse(' + q(settings['material_rule']) + '))), List.of(' + ', '.join(targets) + '), ' + str(settings['sea_level']) + ', false,', '            Optional.of(new Aquifer.Config(' + ', '.join(expr(aquifers[k]) for k in ['barrier','fluid_level_floodedness','fluid_level_spread','lava','exclusion','surface_level']) + ')), false, NoiseGeneratorSettings.DebugFunctions.EMPTY);', '    }', '}']
out = ROOT / 'nekopur-server/src/main/java/org/nekopur/worldgen/TectonicTerrainFactory.java'
out.parent.mkdir(parents=True, exist_ok=True)
source = '\n'.join(lines) + '\n'
source = source.replace('import java.util.*;', '\n'.join('import java.util.' + n + ';' for n in ['HashMap', 'HashSet', 'List', 'Map', 'Optional', 'Set']))
source = source.replace('import net.minecraft.core.*;', '\n'.join('import net.minecraft.core.' + n + ';' for n in ['Direction', 'Holder', 'HolderGetter', 'MappedRegistry', 'RegistrationInfo', 'WritableRegistry']))
source = source.replace('import net.minecraft.resources.*;', 'import net.minecraft.resources.Identifier;\nimport net.minecraft.resources.ResourceKey;')
source = source.replace('import net.minecraft.world.level.levelgen.*;', '\n'.join('import net.minecraft.world.level.levelgen.' + n + ';' for n in ['Aquifer', 'NoiseGeneratorSettings', 'NoiseRouter', 'NoiseSettings', 'SpawnTargetPoint']))
source = source.replace('import net.minecraft.world.level.levelgen.densityfunction.*;', '\n'.join('import net.minecraft.world.level.levelgen.densityfunction.' + n + ';' for n in ['DensityFunction', 'DensityFunctions', 'TilingMode']))
source = source.replace('import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;', 'import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;\nimport net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;')
source = source.replace('factory.registerNoises();', 'factory.registerNoises();\n        ((MappedRegistry<NormalNoise>) noises).bindValuesForNativeBootstrap();')
source = source.replace('        Holder<DensityFunction> result = definition == null ? functions.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(name))) : functions.register(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("nekopur:tectonic/" + name.replace(\':\', \'/\'))), definition, RegistrationInfo.BUILT_IN);', '''        if (definition == null) {
            Holder<DensityFunction> vanilla = functions.getOrThrow(ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(name)));
            definition = remapVanilla(vanilla.value());
            if (definition == vanilla.value()) {
                this.visiting.remove(name);
                built.put(name, vanilla);
                return vanilla;
            }
        }
        ResourceKey<DensityFunction> key = ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse("nekopur:tectonic/" + name.replace(':', '/')));
        if (functions.containsKey(key)) throw new IllegalStateException("Reserved Neko density key already exists: " + key);
        Holder<DensityFunction> result = functions.register(key, definition, RegistrationInfo.BUILT_IN);''')
source = source.replace('        visiting.remove(name);', '        if (definition != null) ((MappedRegistry<DensityFunction>) functions).bindValuesForNativeBootstrap();\n        visiting.remove(name);')
source = source.replace('    private DensityFunction definition0()', '''    // Vanilla fallback graphs must also see the overrides, just as a data registry reload would.
    private DensityFunction remapVanilla(DensityFunction original) {
        if (original instanceof DensityFunctions.HolderHolder reference) {
            return function(reference.function().unwrapKey().orElseThrow().identifier().toString());
        }
        DensityFunction rewritten = original.rewriteChildren(this::remapVanilla);
        if (rewritten instanceof NoiseFunction value) {
            return new NoiseFunction(noise(value.noise().unwrapKey().orElseThrow().identifier().toString()), value.xzScale(), value.yScale(), value.shiftX(), value.shiftY(), value.shiftZ());
        }
        if (rewritten instanceof ShiftNoiseFunction.Shift value) {
            return new ShiftNoiseFunction.Shift(noise(value.offsetNoise().unwrapKey().orElseThrow().identifier().toString()));
        }
        if (rewritten instanceof ShiftNoiseFunction.ShiftA value) {
            return new ShiftNoiseFunction.ShiftA(noise(value.offsetNoise().unwrapKey().orElseThrow().identifier().toString()));
        }
        if (rewritten instanceof ShiftNoiseFunction.ShiftB value) {
            return new ShiftNoiseFunction.ShiftB(noise(value.offsetNoise().unwrapKey().orElseThrow().identifier().toString()));
        }
        return rewritten;
    }

    private DensityFunction definition0()''')
# Break constructor trees at commas outside string literals for readable generated code.
formatted = []
for line in source.splitlines():
    if len(line) < 180 or not line.strip().startswith(('return ', 'new ', 'Optional.of(')):
        formatted.append(line)
        continue
    in_string = False
    depth = 0
    buffer = ''
    for character in line:
        if character == '"': in_string = not in_string
        if not in_string:
            if character in '({': depth += 1
            if character in ')}': depth -= 1
        buffer += character
        if character == ',' and not in_string and len(buffer) > 110:
            formatted.append(buffer.rstrip())
            buffer = ' ' * (12 + min(depth, 5) * 4)
    formatted.append(buffer.rstrip())
from tectonic_native_profiles import add_profiles
out.write_text(add_profiles('\n'.join(formatted) + '\n'), encoding='utf-8', newline='\n')
print(f'Converted {len(functions)} density functions, {len(noises)} noises into {out}')
