/**
 * `vscode.languages`: definition and reference providers, run on demand
 * by the host's GotoDeclarationHandler / Find References action against
 * a TextDocument snapshot read from disk.
 */
import * as path from 'path';
import type * as api from 'vscode-api';
import type { DefinitionParams, Location as WireLocation, ReferencesParams } from '../protocol';
import { SnapshotTextDocument } from './document';
import { globToRegExp } from './glob';
import { Disposable, neverCancelled, Position } from './types';

type registration<P> = { selector: api.DocumentSelector; provider: P };

const definitionProviders: Array<registration<api.DefinitionProvider>> = [];
const referenceProviders: Array<registration<api.ReferenceProvider>> = [];

const matchesFilter = (filter: api.DocumentFilter | string, document: api.TextDocument): boolean => {
	if (typeof filter === 'string') {
		return filter === '*' || filter === document.languageId;
	}
	if (filter.language && filter.language !== '*' && filter.language !== document.languageId) {
		return false;
	}
	if (filter.scheme && filter.scheme !== '*' && filter.scheme !== document.uri.scheme) {
		return false;
	}
	if (filter.pattern) {
		const glob = typeof filter.pattern === 'string' ? filter.pattern : filter.pattern.pattern;
		const subject = typeof filter.pattern === 'string'
			? document.uri.fsPath.split(path.sep).join('/')
			: path.relative(filter.pattern.baseUri?.fsPath ?? filter.pattern.base, document.uri.fsPath).split(path.sep).join('/');
		if (!globToRegExp(glob).test(subject)) {
			return false;
		}
	}
	return true;
};

const matches = (selector: api.DocumentSelector, document: api.TextDocument): boolean => {
	const filters = Array.isArray(selector) ? selector : [selector as api.DocumentFilter | string];
	return filters.some(filter => matchesFilter(filter, document));
};

const register = <P>(list: Array<registration<P>>, selector: api.DocumentSelector, provider: P): Disposable => {
	const entry = { selector, provider };
	list.push(entry);
	return new Disposable(() => {
		const index = list.indexOf(entry);
		if (index !== -1) {
			list.splice(index, 1);
		}
	});
};

const toWireLocation = (target: api.Location | api.LocationLink): WireLocation => {
	const isLink = 'targetUri' in target;
	const uri = isLink ? target.targetUri : target.uri;
	const range = isLink ? target.targetSelectionRange ?? target.targetRange : target.range;
	const location: WireLocation = {
		path: uri.fsPath,
		range: {
			start: { line: range.start.line, character: range.start.character },
			end: { line: range.end.line, character: range.end.character }
		}
	};
	return location;
};

const normalise = (result: api.Definition | api.LocationLink[] | api.Location[] | null | undefined): WireLocation[] => {
	if (!result) {
		return [];
	}
	const list: Array<api.Location | api.LocationLink> = Array.isArray(result) ? result : [result];
	return list.map(toWireLocation);
};

const snapshotFor = (fsPath: string, position: { line: number; character: number }) => {
	const document = SnapshotTextDocument.fromFile(fsPath);
	return { document, position: new Position(position.line, position.character) };
};

/** host → sidecar `definition/provide` */
export const provideDefinitions = async (params: DefinitionParams): Promise<WireLocation[]> => {
	const { document, position } = snapshotFor(params.path, params.position);
	const results = await Promise.all(definitionProviders
		.filter(entry => matches(entry.selector, document))
		.map(async entry => normalise(await entry.provider.provideDefinition(document, position, neverCancelled))));
	return results.flat();
};

/** host → sidecar `references/provide` */
export const provideReferences = async (params: ReferencesParams): Promise<WireLocation[]> => {
	const { document, position } = snapshotFor(params.path, params.position);
	const results = await Promise.all(referenceProviders
		.filter(entry => matches(entry.selector, document))
		.map(async entry => normalise(await entry.provider.provideReferences(document, position, { includeDeclaration: params.includeDeclaration }, neverCancelled))));
	return results.flat();
};

export const languages: Pick<typeof api.languages, 'registerDefinitionProvider' | 'registerReferenceProvider'> = {
	registerDefinitionProvider: (selector, provider) => register(definitionProviders, selector, provider),
	registerReferenceProvider: (selector, provider) => register(referenceProviders, selector, provider)
};
