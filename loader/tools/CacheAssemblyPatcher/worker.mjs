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
    const [config, input, options] = await Promise.all([
        fetch('./mono-config.json').then(r => { if (!r.ok) throw Error('Missing runtime config'); return r.json(); }),
        fetch('./runtime-input').then(r => { if (!r.ok) throw Error('Missing assembly'); return r.arrayBuffer(); }),
        fetch('./runtime-options.json').then(r => { if (!r.ok) throw Error('Missing game target'); return r.json(); }),
    ]);
    const runtime = await dotnet.withConfig(config).create();
    const exports = await runtime.getAssemblyExports(assembly);
    output = exports.Cesium.CachePatcher.AssemblyPatch.Patch(new Uint8Array(input), options.package, options.reference);
    globalThis.patchStatus = { size: output.length };
} catch (error) {
    const message = String(error.message || error);
    const unsupported = message.includes('CESIUM_UNSUPPORTED_RUNTIME: ');
    globalThis.patchStatus = { error: message.slice(0, 700), unsupported };
}
