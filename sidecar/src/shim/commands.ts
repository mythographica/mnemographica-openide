/**
 * `vscode.commands`: the extension's command registry. executeCommand
 * resolves, in order: a locally registered command, a built-in
 * `<viewId>.focus` (→ view/focus), anything else → the host's
 * commands/executeBuiltin.
 */
import { ResponseError } from 'vscode-jsonrpc/node';
import type * as api from 'vscode-api';
import { log, rpc } from '../host';
import { ExecuteBuiltinRequest, METHOD_NOT_FOUND, ViewFocusNotification, type CommandExecuteParams } from '../protocol';
import { resolveTreeItemRef } from './tree';
import { Disposable } from './types';
import { isArgumentRef, resolveArgumentRef, toWire } from './wire';

type handler = { callback: (...args: any[]) => any; thisArg: unknown };

const registry = new Map<string, handler>();
const viewIds = new Set<string>();

/** Views whose `<id>.focus` built-in command exists (contributed or created) */
export const addKnownView = (viewId: string): void => {
	viewIds.add(viewId);
};

export const registeredCommandIds = (): string[] => [...registry.keys()];

const FOCUS_SUFFIX = '.focus';

/** `<viewId>.focus` of a known view: the one built-in command the sidecar answers itself */
const focusViewId = (command: string): string | undefined => {
	if (!command.endsWith(FOCUS_SUFFIX)) {
		return undefined;
	}
	const viewId = command.slice(0, -FOCUS_SUFFIX.length);
	return viewIds.has(viewId) ? viewId : undefined;
};

const executeCommand = async <T = unknown>(command: string, ...rest: any[]): Promise<T> => {
	const local = registry.get(command);
	if (local) {
		const result = await local.callback.apply(local.thisArg, rest);
		return result;
	}
	const viewId = focusViewId(command);
	if (viewId !== undefined) {
		await rpc().sendNotification(ViewFocusNotification, { viewId });
		return undefined as T;
	}
	try {
		const result = await rpc().sendRequest(ExecuteBuiltinRequest, { command, args: rest.map(arg => toWire(arg)) });
		return result as T;
	} catch (error) {
		if (error instanceof ResponseError && error.code === METHOD_NOT_FOUND) {
			log.warn(`command '${command}' is not available in OpenIDE`);
		}
		throw error;
	}
};

export const commands: Pick<typeof api.commands, 'registerCommand' | 'executeCommand'> = {
	registerCommand (command: string, callback: (...args: any[]) => any, thisArg?: any): Disposable {
		if (registry.has(command)) {
			throw new Error(`command '${command}' already exists`);
		}
		const entry: handler = { callback, thisArg };
		registry.set(command, entry);
		const disposable = new Disposable(() => {
			if (registry.get(command) === entry) {
				registry.delete(command);
			}
		});
		return disposable;
	},
	executeCommand
};

/**
 * host → sidecar `command/execute`: local commands and `<viewId>.focus` (the
 * status bar item's command is `mnemonicaTypes.focus`), argument references
 * resolved back to live values
 */
export const executeFromHost = async (params: CommandExecuteParams): Promise<unknown> => {
	if (!registry.has(params.command) && focusViewId(params.command) === undefined) {
		throw new ResponseError(METHOD_NOT_FOUND, `command '${params.command}' not found`);
	}
	const args = (params.args ?? []).map(arg => (isArgumentRef(arg) ? resolveArgumentRef(arg) : arg));
	// An item context-menu command receives the clicked element first
	if (params.context) {
		args.unshift(resolveTreeItemRef(params.context));
	}
	const result = await executeCommand(params.command, ...args);
	return toWire(result);
};
