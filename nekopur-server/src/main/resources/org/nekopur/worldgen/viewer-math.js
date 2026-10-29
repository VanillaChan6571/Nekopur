export const WORLD_LIMIT = 29999984;

export function worldAt(view, width, height, px, py) {
    return { x: view.x + (px - width / 2) * view.bpp, z: view.z + (py - height / 2) * view.bpp };
}

export function panBy(view, dx, dy) {
    return { ...view, x: view.x - dx * view.bpp, z: view.z - dy * view.bpp };
}

export function zoomAt(view, width, height, px, py, factor, size = 256) {
    const anchor = worldAt(view, width, height, px, py);
    const bpp = Math.max(0.05, Math.min(size * 4096 / Math.max(width, height, 1), view.bpp * factor));
    return { x: anchor.x - (px - width / 2) * bpp, z: anchor.z - (py - height / 2) * bpp, bpp };
}

export function requestForView(view, width, height, size) {
    const desired = Math.max(width, height, 1) * view.bpp / size;
    const step = Math.min(4096, Math.max(1, 2 ** Math.ceil(Math.log2(desired))));
    const margin = size * step / 2;
    const bound = WORLD_LIMIT - margin;
    return { size, step, x: Math.round(Math.max(-bound, Math.min(bound, view.x))), z: Math.round(Math.max(-bound, Math.min(bound, view.z))) };
}

export function clampView(view, width, height, size) {
    const bpp = Math.max(0.05, Math.min(size * 4096 / Math.max(width, height, 1), view.bpp));
    const request = requestForView({ ...view, bpp }, width, height, size);
    return { x: Math.max(-WORLD_LIMIT + size * request.step / 2, Math.min(WORLD_LIMIT - size * request.step / 2, view.x)),
        z: Math.max(-WORLD_LIMIT + size * request.step / 2, Math.min(WORLD_LIMIT - size * request.step / 2, view.z)), bpp };
}

export function framePixel(frame, x, z) {
    const column = Math.floor((x - frame.minX) / frame.step);
    const row = Math.floor((z - frame.minZ) / frame.step);
    return column < 0 || row < 0 || column >= frame.size || row >= frame.size ? -1 : row * frame.size + column;
}

export function responseIsCurrent(responseId, activeId) { return responseId === activeId; }
