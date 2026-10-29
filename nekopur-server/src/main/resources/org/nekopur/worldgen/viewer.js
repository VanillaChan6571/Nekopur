import { worldAt, panBy, zoomAt, clampView, requestForView, framePixel, responseIsCurrent } from './viewer-math.js';

const $ = id => document.getElementById(id);
const canvas = $('map'), ctx = canvas.getContext('2d');
const params = new URLSearchParams(location.hash.slice(1));
let seed = params.get('seed') || '6571';
let preset = params.get('preset') === 'nekopur:neko-mmo-tall' ? 'nekopur:neko-mmo-tall' : 'nekopur:neko';
let mode = params.get('mode') === 'detailed' ? 'detailed' : 'fast';
let layer = ['terrain', 'elevation', 'biomes'].includes(params.get('layer')) ? params.get('layer') : 'terrain';
let view = { x: Number(params.get('x')) || 0, z: Number(params.get('z')) || 0, bpp: Number(params.get('zoom')) || 0 };
let frame = null, images = {}, ready = false, drag = null, timer = null, controller = null, activeId = 0;
const sampleSize = () => mode === 'detailed' ? 128 : 256;
const status = (text, error = false) => { $('status').textContent = text; $('status').classList.toggle('error', error); };
const humanBiome = name => name.replace('minecraft:', '').replaceAll('_', ' ');
const biomeColor = id => frame.biomeColors?.[id] || '#8da898';

function positionLabels() {
    $('center').textContent = `X ${Math.round(view.x)} · Z ${Math.round(view.z)}`;
    $('goto-x').value = Math.round(view.x); $('goto-z').value = Math.round(view.z);
    $('scale').textContent = `North ↑ · ${(canvas.width * view.bpp / 1000).toFixed(2)} km across`;
    history.replaceState(null, '', '#' + new URLSearchParams({ seed, preset, x: String(Math.round(view.x)), z: String(Math.round(view.z)), zoom: String(view.bpp), mode, layer }));
}

function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = '#1e3039'; ctx.lineWidth = 1;
    for (let x = 0; x < canvas.width; x += 64) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, canvas.height); ctx.stroke(); }
    for (let y = 0; y < canvas.height; y += 64) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(canvas.width, y); ctx.stroke(); }
    const image = images[layer];
    if (frame && image?.complete && image.naturalWidth) {
        const x = canvas.width / 2 + (frame.minX - view.x) / view.bpp;
        const y = canvas.height / 2 + (frame.minZ - view.z) / view.bpp;
        const extent = frame.size * frame.step / view.bpp;
        ctx.imageSmoothingEnabled = false; ctx.drawImage(image, x, y, extent, extent);
        const cx = canvas.width / 2 - view.x / view.bpp, cy = canvas.height / 2 - view.z / view.bpp;
        ctx.strokeStyle = '#fff'; ctx.beginPath(); ctx.moveTo(cx - 6, cy); ctx.lineTo(cx + 6, cy); ctx.moveTo(cx, cy - 6); ctx.lineTo(cx, cy + 6); ctx.stroke();
    }
    positionLabels();
}

function legend() {
    $('legend-title').textContent = layer === 'biomes' ? 'Biome colors' : layer === 'elevation' ? 'Elevation palette' : 'Terrain palette';
    $('legend').replaceChildren();
    const entries = layer === 'biomes' && frame ? frame.biomeNames.map((name, id) => [biomeColor(id), humanBiome(name)])
        : layer === 'elevation' ? [['#286b99', 'Below sea surface'], ['#e7d4a3', 'Low elevation'], ['#9b3d70', 'High elevation']]
        : [['#286b99', 'Water / estimated low ground'], ['#dcc893', 'Coast'], ['#547d48', 'Lowlands'], ['#8c8275', 'Highlands'], ['#f1f0ed', 'Highest terrain']];
    for (const [color, text] of entries) {
        const row = document.createElement('div'), swatch = document.createElement('span');
        swatch.className = 'swatch'; swatch.style.background = color; swatch.style.marginRight = '8px';
        row.append(swatch, document.createTextNode(text)); $('legend').append(row);
    }
}

function frameLabels() {
    if (!frame) return;
    $('resolved-seed').textContent = frame.seed;
    $('generator').textContent = `${frame.preset} · ${frame.version}`;
    $('resolution').textContent = `${frame.step} blocks/sample · ${frame.size} × ${frame.size}`;
    $('download').href = frame.images[layer]; $('download').download = `neko-${frame.seed}-${layer}.png`;
    $('accuracy').textContent = (frame.mode === 'fast' ? 'Overview estimates height and coastlines before final terrain density. ' : 'Detailed mode samples solid noise-column height. ')
        + 'Biomes are sampled at ground or sea surface. Water colors mark low ground. Structures, surface decoration, trees and world spawn are not generated.';
    $('biome-list').replaceChildren();
    const counts = new Map(); frame.biomes.forEach(id => counts.set(id, (counts.get(id) || 0) + 1));
    for (const [id, count] of [...counts].sort((a, b) => b[1] - a[1])) {
        const row = document.createElement('div'), swatch = document.createElement('span'), name = document.createElement('span'), percent = document.createElement('span');
        row.className = 'biome'; swatch.className = 'swatch'; swatch.style.background = biomeColor(id);
        name.className = 'name'; name.textContent = humanBiome(frame.biomeNames[id]);
        percent.className = 'percent'; percent.textContent = `${(count / frame.biomes.length * 100).toFixed(1)}%`;
        row.append(swatch, name, percent); $('biome-list').append(row);
    }
    legend();
}

function queuePreview(delay = 180) {
    clearTimeout(timer); controller?.abort(); activeId++;
    const id = activeId;
    status(ready ? 'Sampling this area…' : 'Connecting to the native generator…');
    if (ready) timer = setTimeout(() => requestPreview(id), delay);
}

async function requestPreview(id) {
    if (!responseIsCurrent(id, activeId)) return;
    controller = new AbortController();
    const request = { seed, preset, mode, ...requestForView(view, canvas.width, canvas.height, sampleSize()) };
    try {
        const response = await fetch('/api/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal: controller.signal });
        if (!responseIsCurrent(id, activeId)) return;
        if (response.status === 429) { status('Finishing the previous area…'); timer = setTimeout(() => requestPreview(id), 350); return; }
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || `Generator returned ${response.status}`);
        if (!responseIsCurrent(id, activeId)) return;
        const nextImages = {};
        await Promise.all(Object.entries(result.images).map(([name, url]) => new Promise((resolve, reject) => {
            const image = new Image(); nextImages[name] = image;
            image.onload = resolve; image.onerror = () => reject(new Error('Could not decode the generated map')); image.src = url;
        })));
        if (!responseIsCurrent(id, activeId)) return;
        frame = result; images = nextImages; frameLabels(); draw();
        status(`${result.mode === 'fast' ? 'Overview ready' : 'Terrain columns ready'} · ${result.step} blocks/sample`);
    } catch (error) {
        if (error.name !== 'AbortError' && responseIsCurrent(id, activeId)) status(error.message + ' · try Explore seed again', true);
    }
}

function move(next, delay = 180) {
    view = clampView(next, canvas.width, canvas.height, sampleSize()); draw(); queuePreview(delay);
}

function clearFrame() {
    frame = null; images = {}; $('resolved-seed').textContent = '—'; $('resolution').textContent = '—';
    $('biome-list').replaceChildren(); $('download').removeAttribute('href');
    $('coords').textContent = 'Hover over the map'; $('height').textContent = '—'; $('biome').textContent = '—';
}

function exploreSeed() {
    const next = $('seed-input').value.trim();
    if (!next) { status('Enter a numeric or text seed', true); return; }
    seed = next; clearFrame();
    move({ x: 0, z: 0, bpp: 32768 / Math.max(canvas.width, canvas.height, 1) }, 0);
}

$('seed-form').onsubmit = event => { event.preventDefault(); exploreSeed(); };
$('random').onclick = () => {
    const words = crypto.getRandomValues(new Uint32Array(2));
    $('seed-input').value = BigInt.asIntN(64, (BigInt(words[0]) << 32n) | BigInt(words[1])).toString(); exploreSeed();
};
$('location-form').onsubmit = event => {
    event.preventDefault(); const x = Number($('goto-x').value), z = Number($('goto-z').value);
    if (!Number.isFinite(x) || !Number.isFinite(z)) { status('Enter valid X and Z coordinates', true); return; }
    move({ ...view, x, z }, 0);
};
$('origin').onclick = () => move({ ...view, x: 0, z: 0 }, 0);
$('mode').onchange = () => { mode = $('mode').value; clearFrame(); move(view, 0); };
$('preset').onchange = () => { preset = $('preset').value; clearFrame(); move(view, 0); };
document.querySelectorAll('[data-layer]').forEach(button => button.onclick = () => {
    layer = button.dataset.layer; document.querySelectorAll('[data-layer]').forEach(b => b.classList.toggle('active', b === button));
    frameLabels(); legend(); draw();
});
const zoomCenter = factor => move(zoomAt(view, canvas.width, canvas.height, canvas.width / 2, canvas.height / 2, factor, sampleSize()));
$('zoom-in').onclick = () => zoomCenter(0.5); $('zoom-out').onclick = () => zoomCenter(2);
function point(event) { const rect = canvas.getBoundingClientRect(); return { x: (event.clientX - rect.left) * canvas.width / rect.width, y: (event.clientY - rect.top) * canvas.height / rect.height }; }
canvas.addEventListener('wheel', event => {
    event.preventDefault(); const p = point(event);
    move(zoomAt(view, canvas.width, canvas.height, p.x, p.y, Math.exp(Math.max(-250, Math.min(250, event.deltaY)) * 0.002), sampleSize()));
}, { passive: false });
canvas.onpointerdown = event => { drag = point(event); canvas.focus({ preventScroll: true }); canvas.setPointerCapture(event.pointerId); canvas.style.cursor = 'grabbing'; clearTimeout(timer); controller?.abort(); activeId++; };
canvas.onpointerup = canvas.onpointercancel = () => { if (drag) { drag = null; canvas.style.cursor = 'grab'; queuePreview(0); } };
canvas.onpointermove = event => {
    const p = point(event);
    if (drag) { view = clampView(panBy(view, p.x - drag.x, p.y - drag.y), canvas.width, canvas.height, sampleSize()); drag = p; draw(); }
    const world = worldAt(view, canvas.width, canvas.height, p.x, p.y);
    $('coords').textContent = `X ${Math.floor(world.x)} · Z ${Math.floor(world.z)}`;
    const index = frame ? framePixel(frame, world.x, world.z) : -1;
    $('height').textContent = index < 0 ? 'Waiting for this area' : `Y ${frame.elevation[index]}${frame.mode === 'fast' ? ' (estimated)' : ''}`;
    $('biome').textContent = index < 0 ? '—' : humanBiome(frame.biomeNames[frame.biomes[index]]);
};
canvas.onkeydown = event => {
    const moves = { ArrowLeft: [canvas.width / 4, 0], ArrowRight: [-canvas.width / 4, 0], ArrowUp: [0, canvas.height / 4], ArrowDown: [0, -canvas.height / 4] };
    if (moves[event.key]) { event.preventDefault(); move(panBy(view, ...moves[event.key])); }
    else if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomCenter(0.5); }
    else if (event.key === '-') { event.preventDefault(); zoomCenter(2); }
};
$('json').onclick = () => {
    if (!frame) return;
    const { images, ...samples } = frame, url = URL.createObjectURL(new Blob([JSON.stringify(samples)], { type: 'application/json' })), link = document.createElement('a');
    link.href = url; link.download = `neko-${frame.seed}-samples.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
};
function resize() {
    canvas.width = canvas.clientWidth; canvas.height = canvas.clientHeight;
    if (!view.bpp || !Number.isFinite(view.bpp)) view.bpp = 32768 / Math.max(canvas.width, canvas.height, 1);
    view = clampView(view, canvas.width, canvas.height, sampleSize()); draw(); queuePreview();
}
$('seed-input').value = seed; $('mode').value = mode; $('preset').value = preset;
document.querySelectorAll('[data-layer]').forEach(b => b.classList.toggle('active', b.dataset.layer === layer));
legend(); resize(); window.addEventListener('resize', resize);
try {
    const response = await fetch('/api/info'); if (!response.ok) throw new Error('The local generator is unavailable');
    const info = await response.json(); $('generator').textContent = `${info.preset} · ${info.version}`;
    if (!params.has('seed') && info.initialSeed) { seed = info.initialSeed; $('seed-input').value = seed; }
    if (!params.has('x')) view.x = info.initialX ?? 0;
    if (!params.has('z')) view.z = info.initialZ ?? 0;
    if (!params.has('mode') && info.initialMode) { mode = info.initialMode; $('mode').value = mode; }
    if (!params.has('preset') && info.initialPreset) { preset = info.initialPreset; $('preset').value = preset; }
    view = clampView(view, canvas.width, canvas.height, sampleSize()); draw();
    ready = true; queuePreview(0);
} catch (error) { status(error.message + ' · keep the viewer launcher running', true); }
