import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../nekopur-server/src/main/resources/org/nekopur/worldgen/viewer-math.js', import.meta.url));
const math = await import('data:text/javascript;base64,' + source.toString('base64'));

test('zoom keeps the world coordinate under the pointer fixed', () => {
    const view = { x: -10001, z: 2317, bpp: 128 };
    const point = math.worldAt(view, 800, 600, 123, 477);
    const zoomed = math.zoomAt(view, 800, 600, 123, 477, 0.5);
    assert.deepEqual(math.worldAt(zoomed, 800, 600, 123, 477), point);
});
test('drag direction and negative frame coordinates agree', () => {
    assert.deepEqual(math.panBy({ x: -17, z: 2, bpp: 16 }, 5, -3), { x: -97, z: 50, bpp: 16 });
    const frame = { minX: -81, minZ: -62, step: 16, size: 8 };
    assert.equal(math.framePixel(frame, -81, -62), 0);
    assert.equal(math.framePixel(frame, -65, -46), 9);
    assert.equal(math.framePixel(frame, -82, -62), -1);
    assert.equal(math.framePixel(frame, 47, -62), -1);
});
test('zooming beyond either limit never drifts the cursor anchor', () => {
    for (const size of [128, 256]) {
        for (const [bpp, factor] of [[0.05, 0.5], [size * 4096 / 800, 2]]) {
            const view = { x: 0, z: 0, bpp };
            const before = math.worldAt(view, 800, 600, 0, 0);
            const after = math.clampView(math.zoomAt(view, 800, 600, 0, 0, factor, size), 800, 600, size);
            assert.deepEqual(math.worldAt(after, 800, 600, 0, 0), before);
        }
    }
});
test('requested square covers viewport and never exceeds the world border', () => {
    for (const size of [128, 256]) {
        const view = math.clampView({ x: 29999984, z: -29999984, bpp: 100000 }, 1200, 700, size);
        const request = math.requestForView(view, 1200, 700, size);
        assert.ok(request.step >= 1 && request.step <= 4096);
        assert.ok(request.size * request.step >= 1200 * view.bpp);
        assert.ok(Math.abs(request.x) + size * request.step / 2 <= math.WORLD_LIMIT);
        assert.ok(Math.abs(request.z) + size * request.step / 2 <= math.WORLD_LIMIT);
    }
});
test('late responses from previous seeds and viewports cannot replace the active map', () => {
    assert.equal(math.responseIsCurrent(4, 5), false);
    assert.equal(math.responseIsCurrent(5, 5), true);
});
