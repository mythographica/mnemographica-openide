/**
 * The sidecar's handle on the host: the JSON-RPC connection (set once by
 * main.ts), the session parameters from `initialize`, and the sidecar's
 * own logger. Shim modules reach the IDE only through here.
 */
import type { MessageConnection } from 'vscode-jsonrpc/node';
import { LogNotification, type InitializeParams, type LogLevel } from './protocol';

let connection: MessageConnection | undefined;
let session: InitializeParams | undefined;

export function setConnection (value: MessageConnection): void {
	connection = value;
}

export function rpc (): MessageConnection {
	if (!connection) {
		throw new Error('host connection is not established');
	}
	return connection;
}

export function setSession (value: InitializeParams): void {
	session = value;
}

export function getSession (): InitializeParams {
	if (!session) {
		throw new Error('sidecar is not initialized');
	}
	return session;
}

const debugEnabled = Boolean(process.env.MNEMO_SIDECAR_DEBUG);

const emit = (level: LogLevel, message: string): void => {
	if (level === 'debug' && !debugEnabled) {
		return;
	}
	process.stderr.write(`[sidecar:${level}] ${message}\n`);
	if (connection && level !== 'debug') {
		connection.sendNotification(LogNotification, { level, message }).catch(() => undefined);
	}
};

export const log = {
	debug: (message: string) => emit('debug', message),
	info: (message: string) => emit('info', message),
	warn: (message: string) => emit('warn', message),
	error: (message: string) => emit('error', message)
};

const warnedOnce = new Set<string>();

/** Logs once per key: for shim members that exist for type conformance only */
export function warnUnsupported (member: string): void {
	if (warnedOnce.has(member)) {
		return;
	}
	warnedOnce.add(member);
	log.warn(`vscode shim: ${member} is not supported in OpenIDE — ignored`);
}
