/**
 * TextDocument snapshots. The IDE owns the editors; the sidecar only
 * needs a read-only view of a file's text for the navigation providers
 * and `workspace.openTextDocument`, read from disk at creation.
 */
import * as fs from 'fs';
import * as path from 'path';
import type * as api from 'vscode-api';
import { Position, Range, Uri } from './types';

const LANGUAGE_BY_EXTENSION: Record<string, string> = {
	'.ts': 'typescript',
	'.mts': 'typescript',
	'.cts': 'typescript',
	'.tsx': 'typescriptreact',
	'.js': 'javascript',
	'.mjs': 'javascript',
	'.cjs': 'javascript',
	'.jsx': 'javascriptreact',
	'.json': 'json',
	'.md': 'markdown',
	'.html': 'html',
	'.css': 'css'
};

export const languageIdOf = (fileName: string): string => LANGUAGE_BY_EXTENSION[path.extname(fileName).toLowerCase()] ?? 'plaintext';

// VS Code's default word definition (DEFAULT_WORD_REGEXP)
const DEFAULT_WORD = /(-?\d*\.\d\w*)|([^`~!@#$%^&*()\-=+[{\]}\\|;:'",.<>/?\s]+)/g;

const EOL_LF = 1 as api.EndOfLine;
const EOL_CRLF = 2 as api.EndOfLine;

export class SnapshotTextDocument implements api.TextDocument {
	readonly fileName: string;
	readonly isUntitled: boolean;
	readonly languageId: string;
	readonly version = 1;
	readonly isDirty = false;
	readonly isClosed = false;
	readonly eol: api.EndOfLine;
	private readonly lines: string[];
	private readonly lineStarts: number[];

	constructor (readonly uri: Uri, private readonly text: string, languageId?: string) {
		this.fileName = uri.scheme === 'file' ? uri.fsPath : uri.path;
		this.isUntitled = uri.scheme === 'untitled';
		this.languageId = languageId ?? languageIdOf(this.fileName);
		this.eol = text.includes('\r\n') ? EOL_CRLF : EOL_LF;
		this.lines = text.split(/\r\n|\r|\n/);
		this.lineStarts = [];
		const breaks = /\r\n|\r|\n/g;
		let offset = 0;
		this.lineStarts.push(0);
		for (let match = breaks.exec(text); match; match = breaks.exec(text)) {
			offset = match.index + match[0].length;
			this.lineStarts.push(offset);
		}
	}

	static fromFile (fsPath: string): SnapshotTextDocument {
		const text = fs.readFileSync(fsPath, 'utf8');
		const document = new SnapshotTextDocument(Uri.file(fsPath), text);
		return document;
	}

	get lineCount (): number {
		return this.lines.length;
	}

	save (): Thenable<boolean> {
		return Promise.resolve(false);
	}

	lineAt (line: number): api.TextLine;
	lineAt (position: api.Position): api.TextLine;
	lineAt (lineOrPosition: number | api.Position): api.TextLine {
		const lineNumber = typeof lineOrPosition === 'number' ? lineOrPosition : lineOrPosition.line;
		if (lineNumber < 0 || lineNumber >= this.lines.length) {
			throw new Error(`Illegal value for line: ${lineNumber}`);
		}
		const text = this.lines[lineNumber];
		const nextStart = this.lineStarts[lineNumber + 1];
		const breakLength = nextStart === undefined ? 0 : nextStart - this.lineStarts[lineNumber] - text.length;
		const firstNonWhitespace = text.search(/\S/);
		const line: api.TextLine = {
			lineNumber,
			text,
			range: new Range(lineNumber, 0, lineNumber, text.length),
			rangeIncludingLineBreak: breakLength > 0 ? new Range(lineNumber, 0, lineNumber + 1, 0) : new Range(lineNumber, 0, lineNumber, text.length),
			firstNonWhitespaceCharacterIndex: firstNonWhitespace === -1 ? text.length : firstNonWhitespace,
			isEmptyOrWhitespace: firstNonWhitespace === -1
		};
		return line;
	}

	offsetAt (position: api.Position): number {
		const valid = this.validatePosition(position);
		return this.lineStarts[valid.line] + valid.character;
	}

	positionAt (offset: number): Position {
		const clamped = Math.max(0, Math.min(Math.floor(offset), this.text.length));
		let line = 0;
		while (line + 1 < this.lineStarts.length && this.lineStarts[line + 1] <= clamped) {
			line++;
		}
		const position = new Position(line, Math.min(clamped - this.lineStarts[line], this.lines[line].length));
		return position;
	}

	getText (range?: api.Range): string {
		if (!range) {
			return this.text;
		}
		const valid = this.validateRange(range);
		return this.text.substring(this.offsetAt(valid.start), this.offsetAt(valid.end));
	}

	getWordRangeAtPosition (position: api.Position, regex?: RegExp): Range | undefined {
		const valid = this.validatePosition(position);
		const text = this.lines[valid.line];
		const flags = regex ? (regex.flags.includes('g') ? regex.flags : `${regex.flags}g`) : DEFAULT_WORD.flags;
		const pattern = new RegExp(regex ? regex.source : DEFAULT_WORD.source, flags);
		for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
			const start = match.index;
			const end = start + match[0].length;
			if (start <= valid.character && valid.character <= end && end > start) {
				return new Range(valid.line, start, valid.line, end);
			}
			if (match[0].length === 0) {
				pattern.lastIndex++;
			}
		}
		return undefined;
	}

	validateRange (range: api.Range): Range {
		return new Range(this.validatePosition(range.start), this.validatePosition(range.end));
	}

	validatePosition (position: api.Position): Position {
		const line = Math.max(0, Math.min(position.line, this.lines.length - 1));
		const character = Math.max(0, Math.min(position.character, this.lines[line].length));
		const valid = new Position(line, character);
		return valid;
	}
}
