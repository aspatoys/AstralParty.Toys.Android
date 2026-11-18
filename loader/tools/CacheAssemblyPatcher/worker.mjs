import { dotnet } from './dotnet.js';

const assembly = 'CacheAssemblyPatcher.dll';
let output;
globalThis.patchStatus = null;
globalThis.patchChunk = (start, length) => {
    const bytes = output.subarray(start, start + length);
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 8192)
        binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
    return btoa(binary);
};

try {
    const [config, input] = await Promise.all([
        fetch('./mono-config.json').then(r => { if (!r.ok) throw Error('Missing runtime config'); return r.json(); }),
        fetch('./runtime-input').then(r => { if (!r.ok) throw Error('Missing assembly'); return r.arrayBuffer(); }),
    ]);
    const runtime = await dotnet.withConfig(config).create();
    const exports = await runtime.getAssemblyExports(assembly);
    output = exports.Cesium.CachePatcher.AssemblyPatch.Patch(new Uint8Array(input));
    globalThis.patchStatus = { size: output.length };
} catch (error) {
    globalThis.patchStatus = { error: String(error.message || error).slice(0, 700) };
}
