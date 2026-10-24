/**
 * Build-time virtual module (esbuild.mjs): one lazy loader per file under
 * mnemographica/src/models, keyed by its path relative to that directory
 * without the extension ("Registry", "Scene3D").
 */
declare module 'mnemographica:models' {
	export const models: Record<string, () => unknown>;
}
