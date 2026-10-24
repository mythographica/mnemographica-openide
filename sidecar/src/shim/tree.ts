/**
 * Tree views. The host renders a generic JSON tree; this side keeps the
 * live provider elements and hands out ids for them.
 *
 * Identity: an element is "the same" across getChildren calls when its
 * TreeItem.id is equal (VS Code's rule), else when it has the same label
 * at the same sibling rank under the same parent. Such an element keeps
 * its node id ("<viewId>:<n>") for as long as it stays alive; an
 * onDidChangeTreeData event drops the ids of the invalidated subtree
 * (the node itself keeps its id when a single element is invalidated).
 */
import * as path from 'path';
import type * as api from 'vscode-api';
import { getSession, log, rpc } from '../host';
import {
	TreeInvalidateNotification,
	TreeRegisteredNotification,
	TreeRevealNotification,
	TreeSetMetaNotification,
	type ExpansionChangedParams,
	type GetChildrenParams,
	type GetParentParams,
	type SelectionChangedParams,
	type TreeItemRef,
	type TreeNode,
	type TreeSetMetaParams,
	type VisibilityChangedParams
} from '../protocol';
import { EventEmitter, ThemeIcon, Uri } from './types';
import { releaseArguments, wireArgument } from './wire';

type nodeRecord<T> = {
	id: string;
	key: string;
	element: T;
	parentId: string | null;
	/** undefined until the node's children are fetched */
	childIds: string[] | undefined;
};

const views = new Map<string, TreeViewImpl<any>>();

const labelText = (item: api.TreeItem): string => {
	const label = item.label;
	if (typeof label === 'string') {
		return label;
	}
	if (label && typeof label === 'object') {
		return label.label;
	}
	return item.resourceUri ? path.basename(item.resourceUri.fsPath) : '';
};

const asPath = (value: string | api.Uri): string => (typeof value === 'string' ? value : value.fsPath);

class TreeViewImpl<T> implements api.TreeView<T> {
	private readonly onDidExpandEmitter = new EventEmitter<api.TreeViewExpansionEvent<T>>();
	private readonly onDidCollapseEmitter = new EventEmitter<api.TreeViewExpansionEvent<T>>();
	private readonly onDidChangeSelectionEmitter = new EventEmitter<api.TreeViewSelectionChangeEvent<T>>();
	private readonly onDidChangeVisibilityEmitter = new EventEmitter<api.TreeViewVisibilityChangeEvent>();
	readonly onDidExpandElement = this.onDidExpandEmitter.event;
	readonly onDidCollapseElement = this.onDidCollapseEmitter.event;
	readonly onDidChangeSelection = this.onDidChangeSelectionEmitter.event;
	readonly onDidChangeVisibility = this.onDidChangeVisibilityEmitter.event;

	private currentSelection: readonly T[] = [];
	private isVisible = false;
	private meta: Omit<TreeSetMetaParams, 'viewId'> = {};
	private counter = 0;
	private readonly byId = new Map<string, nodeRecord<T>>();
	private readonly byKey = new Map<string, string>();
	private readonly byElement = new Map<T, string>();
	private rootIds: string[] | undefined;
	private readonly providerSubscription: api.Disposable | undefined;
	// getChildren / invalidate / reveal interleave across awaits on the
	// provider; they run one at a time so the id maps stay consistent
	private queue: Promise<unknown> = Promise.resolve();

	constructor (readonly viewId: string, private readonly provider: api.TreeDataProvider<T>) {
		this.providerSubscription = provider.onDidChangeTreeData?.((changed) => {
			this.serialized(() => this.invalidate(changed)).catch((error: unknown) => log.error(`[${viewId}] invalidate failed: ${String(error)}`));
		});
	}

	get selection (): readonly T[] {
		return this.currentSelection;
	}

	get visible (): boolean {
		return this.isVisible;
	}

	get message (): string | undefined {
		return this.meta.message;
	}

	set message (value: string | undefined) {
		this.setMeta({ message: value ?? '' });
	}

	get title (): string | undefined {
		return this.meta.title;
	}

	set title (value: string | undefined) {
		this.setMeta({ title: value ?? '' });
	}

	get description (): string | undefined {
		return this.meta.description;
	}

	set description (value: string | undefined) {
		this.setMeta({ description: value ?? '' });
	}

	get badge (): api.ViewBadge | undefined {
		return this.meta.badge;
	}

	set badge (value: api.ViewBadge | undefined) {
		this.setMeta({ badge: value ? { value: value.value, tooltip: value.tooltip } : { value: 0, tooltip: '' } });
	}

	private setMeta (change: Omit<TreeSetMetaParams, 'viewId'>): void {
		this.meta = { ...this.meta, ...change };
		rpc().sendNotification(TreeSetMetaNotification, { viewId: this.viewId, ...change }).catch(() => undefined);
	}

	private serialized<R> (task: () => Promise<R>): Promise<R> {
		const run = this.queue.then(task, task);
		this.queue = run.catch(() => undefined);
		return run;
	}

	// ------------------------------------------------------- id bookkeeping

	private elementId (element: T): string | undefined {
		return this.byElement.get(element);
	}

	/** Forget a node's descendants (and, unless keepSelf, the node itself) */
	private dropSubtree (id: string, keepSelf: boolean): void {
		const record = this.byId.get(id);
		if (!record) {
			return;
		}
		for (const childId of record.childIds ?? []) {
			this.dropSubtree(childId, false);
		}
		record.childIds = undefined;
		if (!keepSelf) {
			this.byId.delete(id);
			if (this.byKey.get(record.key) === id) {
				this.byKey.delete(record.key);
			}
			if (this.byElement.get(record.element) === id) {
				this.byElement.delete(record.element);
			}
			releaseArguments(id);
		}
	}

	private dropAll (): void {
		for (const id of this.byId.keys()) {
			releaseArguments(id);
		}
		this.byId.clear();
		this.byKey.clear();
		this.byElement.clear();
		this.rootIds = undefined;
	}

	private async invalidate (changed: T | T[] | undefined | null | void): Promise<void> {
		const elements = changed === undefined || changed === null ? [] : Array.isArray(changed) ? changed : [changed];
		const ids = elements.map(element => this.elementId(element as T));
		if (elements.length === 0 || ids.some(id => id === undefined)) {
			this.dropAll();
			await rpc().sendNotification(TreeInvalidateNotification, { viewId: this.viewId, itemId: null });
			return;
		}
		for (const id of ids as string[]) {
			this.dropSubtree(id, true);
			await rpc().sendNotification(TreeInvalidateNotification, { viewId: this.viewId, itemId: id });
		}
	}

	private readonly duplicateIdsWarned = new Set<string>();

	private warnDuplicateId (itemId: string): void {
		if (!this.duplicateIdsWarned.has(itemId)) {
			this.duplicateIdsWarned.add(itemId);
			log.warn(`[${this.viewId}] TreeItem.id '${itemId}' is used by more than one row (VS Code would reject the tree); rows kept apart`);
		}
	}

	// ------------------------------------------------------------ children

	/** Fetch and register the children of a node (null = roots) */
	private async fetchChildren (parentId: string | null): Promise<Array<{ record: nodeRecord<T>; item: api.TreeItem }>> {
		const parent = parentId === null ? undefined : this.byId.get(parentId);
		if (parentId !== null && !parent) {
			log.debug(`[${this.viewId}] getChildren for a stale id ${parentId}`);
			return [];
		}
		const elements = (await this.provider.getChildren(parent?.element)) ?? [];
		const previous = new Set(parentId === null ? this.rootIds ?? [] : parent?.childIds ?? []);
		const labelRanks = new Map<string, number>();
		const takenNow = new Set<string>();
		const fetched: Array<{ record: nodeRecord<T>; item: api.TreeItem }> = [];
		for (const element of elements) {
			const item = await this.provider.getTreeItem(element);
			let key: string;
			if (item.id !== undefined) {
				key = `id:${item.id}`;
				const holder = this.byId.get(this.byKey.get(key) ?? '');
				if (takenNow.has(key) || (holder && holder.parentId !== parentId)) {
					// VS Code rejects a TreeItem.id used twice in one tree; the
					// host gets one node per row, each with its own id
					this.warnDuplicateId(item.id);
					let rank = 1;
					while (takenNow.has(`${key}@${parentId ?? ''}#${rank}`)) {
						rank++;
					}
					key = `${key}@${parentId ?? ''}#${rank}`;
				}
			} else {
				const label = labelText(item);
				const rank = labelRanks.get(label) ?? 0;
				labelRanks.set(label, rank + 1);
				key = `path:${parentId ?? ''}/${label}#${rank}`;
			}
			takenNow.add(key);
			let id = this.byKey.get(key);
			let record = id === undefined ? undefined : this.byId.get(id);
			if (!record) {
				id = `${this.viewId}:${++this.counter}`;
				record = { id, key, element, parentId, childIds: undefined };
				this.byId.set(id, record);
				this.byKey.set(key, id);
			} else {
				if (record.element !== element && this.byElement.get(record.element) === record.id) {
					this.byElement.delete(record.element);
				}
				record.element = element;
				record.parentId = parentId;
			}
			this.byElement.set(element, record.id);
			previous.delete(record.id);
			fetched.push({ record, item });
		}
		// Elements the provider no longer returns are gone
		for (const goneId of previous) {
			this.dropSubtree(goneId, false);
		}
		const childIds = fetched.map(entry => entry.record.id);
		if (parentId === null) {
			this.rootIds = childIds;
		} else if (parent) {
			parent.childIds = childIds;
		}
		return fetched;
	}

	private toTreeNode (record: nodeRecord<T>, item: api.TreeItem): TreeNode {
		releaseArguments(record.id);
		const node: TreeNode = {
			id: record.id,
			label: labelText(item),
			collapsibleState: (item.collapsibleState ?? 0) as 0 | 1 | 2
		};
		if (typeof item.description === 'string') {
			node.description = item.description;
		} else if (item.description === true && item.resourceUri) {
			node.description = path.dirname(item.resourceUri.fsPath);
		}
		if (typeof item.tooltip === 'string') {
			node.tooltip = item.tooltip;
		} else if (item.tooltip && typeof item.tooltip.value === 'string') {
			node.tooltip = item.tooltip.value;
		}
		const icon = item.iconPath;
		if (icon instanceof ThemeIcon) {
			node.iconId = icon.id;
			if (icon.color) {
				node.iconColor = icon.color.id;
			}
		} else if (typeof icon === 'string' || icon instanceof Uri) {
			node.iconPath = asPath(icon);
		} else if (icon && typeof icon === 'object' && 'light' in icon) {
			node.iconPath = asPath(getSession().theme === 'dark' ? icon.dark : icon.light);
		}
		if (item.contextValue !== undefined) {
			node.contextValue = item.contextValue;
		}
		if (item.command) {
			node.command = { command: item.command.command };
			if (item.command.arguments) {
				node.command.arguments = item.command.arguments.map(arg => wireArgument(record.id, arg));
			}
		}
		if (item.resourceUri) {
			node.resourcePath = item.resourceUri.fsPath;
		}
		return node;
	}

	getChildren (params: GetChildrenParams): Promise<TreeNode[]> {
		return this.serialized(async () => {
			const fetched = await this.fetchChildren(params.itemId);
			return fetched.map(({ record, item }) => this.toTreeNode(record, item));
		});
	}

	getParent (params: GetParentParams): string | null {
		return this.byId.get(params.itemId)?.parentId ?? null;
	}

	elementFor (itemId: string): T | undefined {
		return this.byId.get(itemId)?.element;
	}

	// -------------------------------------------------------- host events

	selectionChanged (params: SelectionChangedParams): void {
		const selection = params.itemIds.map(id => this.byId.get(id)).filter((record): record is nodeRecord<T> => record !== undefined).map(record => record.element);
		this.currentSelection = selection;
		this.onDidChangeSelectionEmitter.fire({ selection });
	}

	visibilityChanged (params: VisibilityChangedParams): void {
		this.isVisible = params.visible;
		this.onDidChangeVisibilityEmitter.fire({ visible: params.visible });
	}

	expansionChanged (params: ExpansionChangedParams): void {
		const record = this.byId.get(params.itemId);
		if (!record) {
			return;
		}
		const emitter = params.expanded ? this.onDidExpandEmitter : this.onDidCollapseEmitter;
		emitter.fire({ element: record.element });
	}

	// -------------------------------------------------------------- reveal

	reveal (element: T, options?: { select?: boolean; focus?: boolean; expand?: boolean | number }): Promise<void> {
		return this.serialized(() => this.revealNow(element, options));
	}

	private async revealNow (element: T, options?: { select?: boolean; focus?: boolean; expand?: boolean | number }): Promise<void> {
		const getParent = this.provider.getParent?.bind(this.provider);
		if (!getParent) {
			throw new Error(`TreeDataProvider of '${this.viewId}' must implement getParent to support reveal`);
		}
		const chain: T[] = [element];
		for (let parent = await getParent(element); parent !== undefined && parent !== null; parent = await getParent(parent)) {
			if (chain.length > 256) {
				throw new Error(`[${this.viewId}] reveal: getParent chain does not terminate`);
			}
			chain.unshift(parent);
		}
		// Register every level from the roots down, so the host can walk
		// tree/getParent from the revealed id
		let parentId: string | null = null;
		let targetId: string | undefined;
		for (const level of chain) {
			const known = parentId === null ? this.rootIds : this.byId.get(parentId)?.childIds;
			const siblings = known
				? known.map(id => ({ record: this.byId.get(id) as nodeRecord<T> }))
				: await this.fetchChildren(parentId);
			targetId = await this.matchSibling(level, siblings.map(entry => entry.record));
			if (targetId === undefined) {
				throw new Error(`[${this.viewId}] reveal: element is not in the tree`);
			}
			parentId = targetId;
		}
		await rpc().sendNotification(TreeRevealNotification, {
			viewId: this.viewId,
			itemId: targetId as string,
			select: options?.select ?? true,
			focus: options?.focus ?? false,
			expand: options?.expand ?? false
		});
	}

	// Identity first, then TreeItem.id, then the label (elements from
	// getParent are often fresh instances of the same row)
	private async matchSibling (element: T, siblings: Array<nodeRecord<T>>): Promise<string | undefined> {
		const byIdentity = siblings.find(record => record.element === element);
		if (byIdentity) {
			return byIdentity.id;
		}
		const item = await this.provider.getTreeItem(element);
		const key = item.id !== undefined ? `id:${item.id}` : undefined;
		const label = labelText(item);
		const labelOfKey = (record: nodeRecord<T>): string | undefined => {
			const prefix = `path:${record.parentId ?? ''}/`;
			return record.key.startsWith(prefix) ? record.key.slice(prefix.length).replace(/#\d+$/, '') : undefined;
		};
		const match = key
			? siblings.find(record => record.key === key)
			: siblings.find(record => labelOfKey(record) === label);
		return match?.id;
	}

	dispose (): void {
		this.providerSubscription?.dispose();
		this.dropAll();
		views.delete(this.viewId);
	}
}

export const createTreeView = <T>(viewId: string, options: api.TreeViewOptions<T>): api.TreeView<T> => {
	if (views.has(viewId)) {
		throw new Error(`tree view '${viewId}' is already registered`);
	}
	if (options.dragAndDropController) {
		log.warn(`[${viewId}] drag and drop is not supported in OpenIDE — ignored`);
	}
	const view = new TreeViewImpl<T>(viewId, options.treeDataProvider);
	views.set(viewId, view);
	rpc().sendNotification(TreeRegisteredNotification, {
		viewId,
		showCollapseAll: options.showCollapseAll ?? false,
		canSelectMany: options.canSelectMany ?? false
	}).catch(() => undefined);
	return view;
};

const viewOf = (viewId: string): TreeViewImpl<unknown> => {
	const view = views.get(viewId);
	if (!view) {
		throw new Error(`unknown tree view '${viewId}'`);
	}
	return view;
};

/** Host → sidecar tree handlers */
export const treeHost = {
	getChildren: (params: GetChildrenParams) => viewOf(params.viewId).getChildren(params),
	getParent: (params: GetParentParams) => viewOf(params.viewId).getParent(params),
	selectionChanged: (params: SelectionChangedParams) => views.get(params.viewId)?.selectionChanged(params),
	visibilityChanged: (params: VisibilityChangedParams) => views.get(params.viewId)?.visibilityChanged(params),
	expansionChanged: (params: ExpansionChangedParams) => views.get(params.viewId)?.expansionChanged(params)
};

export const resolveTreeItemRef = (ref: TreeItemRef): unknown => {
	const element = viewOf(ref.viewId).elementFor(ref.itemId);
	if (element === undefined) {
		throw new Error(`stale tree item ${ref.itemId}`);
	}
	return element;
};
