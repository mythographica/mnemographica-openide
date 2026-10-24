/**
 * VS Code glob patterns (`**`, `*`, `?`, `{a,b}`, `[...]`) compiled to a
 * RegExp over forward-slash relative paths. Used by findFiles and the
 * file system watchers.
 */

const escapeRegExp = (char: string): string => char.replace(/[.+^${}()|[\]\\/]/g, '\\$&');

// Split a brace body on top-level commas: "a,{b,c},d" → ["a", "{b,c}", "d"]
const splitAlternatives = (body: string): string[] => {
	const parts: string[] = [];
	let depth = 0;
	let current = '';
	for (const char of body) {
		if (char === ',' && depth === 0) {
			parts.push(current);
			current = '';
			continue;
		}
		if (char === '{') {
			depth++;
		} else if (char === '}') {
			depth--;
		}
		current += char;
	}
	parts.push(current);
	return parts;
};

const translate = (glob: string): string => {
	let out = '';
	for (let i = 0; i < glob.length; i++) {
		const char = glob[i];
		if (char === '*') {
			if (glob[i + 1] === '*') {
				const atSegmentStart = i === 0 || glob[i - 1] === '/';
				const followedBySlash = glob[i + 2] === '/';
				if (atSegmentStart && followedBySlash) {
					out += '(?:.*/)?';
					i += 2;
				} else {
					out += '.*';
					i += 1;
				}
			} else {
				out += '[^/]*';
			}
		} else if (char === '?') {
			out += '[^/]';
		} else if (char === '{') {
			let depth = 1;
			let j = i + 1;
			for (; j < glob.length && depth > 0; j++) {
				if (glob[j] === '{') {
					depth++;
				} else if (glob[j] === '}') {
					depth--;
				}
			}
			if (depth !== 0) {
				out += '\\{';
				continue;
			}
			const body = glob.slice(i + 1, j - 1);
			out += `(?:${splitAlternatives(body).map(translate).join('|')})`;
			i = j - 1;
		} else if (char === '[') {
			const end = glob.indexOf(']', i + 1);
			if (end === -1) {
				out += '\\[';
				continue;
			}
			let body = glob.slice(i + 1, end);
			if (body.startsWith('!')) {
				body = `^${body.slice(1)}`;
			}
			out += `[${body.replace(/\\/g, '\\\\')}]`;
			i = end;
		} else {
			out += escapeRegExp(char);
		}
	}
	return out;
};

export const globToRegExp = (glob: string): RegExp => new RegExp(`^${translate(glob.replace(/\\/g, '/'))}$`);
