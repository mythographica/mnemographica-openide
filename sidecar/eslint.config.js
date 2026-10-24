// Mirrors mnemographica/eslint.config.js (the extension has no vscode-specific
// lint rules to drop); adds the generated API declarations to the ignores.
const js = require('@eslint/js');
const tseslint = require('typescript-eslint');

const config = tseslint.config(
	{
		ignores: ['dist', 'node_modules', 'src/shim/generated', '**/*.d.ts'],
	},
	{
		files: ['src/**/*.ts'],
		extends: [
			js.configs.recommended,
			...tseslint.configs.recommended,
		],
		languageOptions: {
			ecmaVersion: 2020,
			sourceType: 'module',
		},
		rules: {
			'@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
			'@typescript-eslint/no-explicit-any': 'off',
			'no-console': 'off',
		},
	},
);

module.exports = config;
