/**
 * `vscode.env`: external links open through the IDE's browser launcher.
 */
import type * as api from 'vscode-api';
import { getSession, rpc } from '../host';
import { OpenExternalNotification } from '../protocol';

export const env: Pick<typeof api.env, 'openExternal' | 'appName' | 'uriScheme'> = {
	async openExternal (target: api.Uri): Promise<boolean> {
		await rpc().sendNotification(OpenExternalNotification, { url: target.toString() });
		return true;
	},
	get appName (): string {
		return getSession().ideVersion || 'OpenIDE';
	},
	// The deep-link scheme the host registers for registerUriHandler
	uriScheme: 'mnemonica'
};
