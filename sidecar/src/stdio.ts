/**
 * stdout is reserved for JSON-RPC. This module is the first import of
 * main.ts: before any extension code runs it re-routes every console.*
 * method — and direct process.stdout.write calls — to stderr, and keeps
 * the real stdout writer for the RPC connection only.
 */
import { Writable } from 'stream';
import { format } from 'util';

const realStdoutWrite = process.stdout.write.bind(process.stdout);

const writeErr = (level: string, args: unknown[]): void => {
	process.stderr.write(`[${level}] ${format(...args)}\n`);
};

console.log = (...args: unknown[]) => writeErr('log', args);
console.info = (...args: unknown[]) => writeErr('info', args);
console.warn = (...args: unknown[]) => writeErr('warn', args);
console.error = (...args: unknown[]) => writeErr('error', args);
console.debug = (...args: unknown[]) => writeErr('debug', args);
console.trace = (...args: unknown[]) => writeErr('trace', [...args, new Error('console.trace').stack]);

// A stray process.stdout.write from a dependency would corrupt the RPC
// framing — it lands on stderr instead
process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]) => {
	const callback = rest.find((arg): arg is () => void => typeof arg === 'function');
	return process.stderr.write(chunk, callback);
}) as typeof process.stdout.write;

/** The only writer that reaches the real stdout: the RPC transport */
export const rpcStdout = new Writable({
	write (chunk: Buffer, _encoding, callback) {
		realStdoutWrite(chunk, (error?: Error | null) => callback(error ?? null));
	}
});
process.stdout.on('error', (error) => rpcStdout.destroy(error));
