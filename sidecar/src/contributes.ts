/**
 * InitializeResult from the extension's package.json `contributes`,
 * merged with the commands activate() actually registered. The host
 * builds its tool window, actions, menus and settings page from this —
 * nothing of package.json is hard-coded in Kotlin.
 */
import type { packageJson } from './context';
import type { CommandInfo, ConfigurationProperty, ConfigurationSchema, InitializeResult, MenuContribution, ViewInfo } from './protocol';

type contributedCommand = { command: string; title: string; category?: string; icon?: string | { light: string; dark: string } };
type contributedContainer = { id: string; title: string };
type contributedView = { id: string; name: string };
type contributedConfiguration = { properties?: Record<string, ConfigurationProperty> };

type contributes = {
	commands?: contributedCommand[];
	viewsContainers?: Record<string, contributedContainer[]>;
	views?: Record<string, contributedView[]>;
	menus?: MenuContribution;
	configuration?: contributedConfiguration | contributedConfiguration[];
};

const contributesOf = (manifest: packageJson): contributes => (manifest.contributes ?? {}) as contributes;

export const contributedViews = (manifest: packageJson): ViewInfo[] => {
	const { viewsContainers = {}, views = {} } = contributesOf(manifest);
	const containers = new Map<string, string>();
	for (const list of Object.values(viewsContainers)) {
		for (const container of list) {
			containers.set(container.id, container.title);
		}
	}
	const result: ViewInfo[] = [];
	for (const [containerId, list] of Object.entries(views)) {
		for (const view of list) {
			result.push({ id: view.id, name: view.name, containerId, containerTitle: containers.get(containerId) ?? containerId });
		}
	}
	return result;
};

export const configurationSchema = (manifest: packageJson): ConfigurationSchema => {
	const { configuration } = contributesOf(manifest);
	const sections = Array.isArray(configuration) ? configuration : configuration ? [configuration] : [];
	const schema: ConfigurationSchema = {};
	for (const section of sections) {
		Object.assign(schema, section.properties ?? {});
	}
	return schema;
};

export const configurationDefaults = (manifest: packageJson): Record<string, unknown> => {
	const defaults: Record<string, unknown> = {};
	for (const [key, property] of Object.entries(configurationSchema(manifest))) {
		if (property.default !== undefined) {
			defaults[key] = property.default;
		}
	}
	return defaults;
};

export const buildInitializeResult = (manifest: packageJson, registered: string[]): InitializeResult => {
	const { commands = [], menus = {} } = contributesOf(manifest);
	const contributed = new Set(commands.map(command => command.command));
	const commandInfos: CommandInfo[] = commands.map((command) => {
		const info: CommandInfo = { command: command.command, title: command.title };
		if (command.category) {
			info.category = command.category;
		}
		if (typeof command.icon === 'string') {
			info.icon = command.icon;
		} else if (command.icon) {
			info.icon = command.icon.dark;
		}
		return info;
	});
	for (const command of registered) {
		if (!contributed.has(command)) {
			commandInfos.push({ command, title: command, internal: true });
		}
	}
	const result: InitializeResult = {
		commands: commandInfos,
		views: contributedViews(manifest),
		menus,
		configuration: configurationSchema(manifest)
	};
	return result;
};
