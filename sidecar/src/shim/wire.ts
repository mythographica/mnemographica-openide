/**
 * Values crossing the wire. Paths travel as plain strings (never URIs),
 * positions as `{ line, character }`. Command arguments that are not
 * plain JSON stay in the sidecar and travel as an opaque reference.
 */
import type { ArgumentRef } from '../protocol';
import { Location, Position, Range, Uri } from './types';

/** API value → protocol value: Uri → path, Range/Position → plain, recursive */
export const toWire = (value: unknown, seen: Set<unknown> = new Set()): unknown => {
	if (value instanceof Uri) {
		return value.scheme === 'file' ? value.fsPath : value.toString();
	}
	if (value instanceof Position) {
		return { line: value.line, character: value.character };
	}
	if (value instanceof Range) {
		return { start: toWire(value.start), end: toWire(value.end) };
	}
	if (value instanceof Location) {
		return { path: toWire(value.uri), range: toWire(value.range) };
	}
	if (value === undefined) {
		return null;
	}
	if (value === null || typeof value !== 'object') {
		return typeof value === 'function' || typeof value === 'symbol' || typeof value === 'bigint' ? null : value;
	}
	if (seen.has(value)) {
		return null;
	}
	seen.add(value);
	if (Array.isArray(value)) {
		return value.map(item => toWire(item, seen));
	}
	const out: Record<string, unknown> = {};
	for (const [key, item] of Object.entries(value)) {
		if (item !== undefined && typeof item !== 'function') {
			out[key] = toWire(item, seen);
		}
	}
	return out;
};

/** True for values JSON can carry without loss: primitives, arrays, plain objects, no cycles */
export const isPlainJson = (value: unknown, seen: Set<unknown> = new Set()): boolean => {
	if (value === null || typeof value === 'string' || typeof value === 'boolean') {
		return true;
	}
	if (typeof value === 'number') {
		return Number.isFinite(value);
	}
	if (typeof value !== 'object' || seen.has(value)) {
		return false;
	}
	seen.add(value);
	if (Array.isArray(value)) {
		const plain = value.every(item => isPlainJson(item, seen));
		seen.delete(value);
		return plain;
	}
	const proto = Object.getPrototypeOf(value);
	if (proto !== Object.prototype && proto !== null) {
		return false;
	}
	const plain = Object.values(value).every(item => item === undefined || isPlainJson(item, seen));
	seen.delete(value);
	return plain;
};

let refCounter = 0;
const refValues = new Map<string, unknown>();
const refsByOwner = new Map<string, string[]>();

/** Plain JSON passes through; anything else is parked under `owner` and replaced by a reference */
export const wireArgument = (owner: string, value: unknown): unknown => {
	if (value === undefined) {
		return null;
	}
	if (isPlainJson(value)) {
		return value;
	}
	const key = `${owner}#${++refCounter}`;
	refValues.set(key, value);
	const owned = refsByOwner.get(owner) ?? [];
	owned.push(key);
	refsByOwner.set(owner, owned);
	const ref: ArgumentRef = { $mnemoRef: key };
	return ref;
};

/** Drop every parked argument of an owner (its tree node is gone) */
export const releaseArguments = (owner: string): void => {
	for (const key of refsByOwner.get(owner) ?? []) {
		refValues.delete(key);
	}
	refsByOwner.delete(owner);
};

export const isArgumentRef = (value: unknown): value is ArgumentRef =>
	typeof value === 'object' && value !== null && typeof (value as ArgumentRef).$mnemoRef === 'string';

export const resolveArgumentRef = (ref: ArgumentRef): unknown => {
	if (!refValues.has(ref.$mnemoRef)) {
		throw new Error(`stale command argument ${ref.$mnemoRef}: its tree item was refreshed`);
	}
	return refValues.get(ref.$mnemoRef);
};
