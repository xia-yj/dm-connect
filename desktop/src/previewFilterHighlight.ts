export type PreviewFilterTokenKind =
  | "text"
  | "column"
  | "keyword"
  | "operator"
  | "string"
  | "number";

export type PreviewFilterToken = {
  kind: PreviewFilterTokenKind;
  text: string;
};

const NUMBER = /^(?:\d+(?:\.\d*)?|\.\d+)/;
const STRING = /^'(?:''|[^'])*'?/;

function isIdentStart(char: string): boolean {
  return /[A-Za-z_]/.test(char) || char.charCodeAt(0) > 127;
}

function isIdentPart(char: string): boolean {
  return /[A-Za-z0-9_$]/.test(char) || char.charCodeAt(0) > 127;
}

function readIdentifier(input: string, start: number): string {
  if (!isIdentStart(input[start] ?? "")) return "";
  let end = start + 1;
  while (end < input.length && isIdentPart(input[end]!)) end += 1;
  return input.slice(start, end);
}

/**
 * Lightweight highlighter lexer for preview filters:
 *   COL = 1 AND NAME LIKE '%x%'
 */
export function tokenizePreviewFilter(input: string): PreviewFilterToken[] {
  if (!input) return [];
  const tokens: PreviewFilterToken[] = [];
  let index = 0;

  const push = (kind: PreviewFilterTokenKind, text: string) => {
    if (!text) return;
    const last = tokens[tokens.length - 1];
    if (last && last.kind === kind) last.text += text;
    else tokens.push({ kind, text });
  };

  while (index < input.length) {
    const rest = input.slice(index);
    const space = rest.match(/^\s+/);
    if (space) {
      push("text", space[0]);
      index += space[0].length;
      continue;
    }

    const keyword = rest.match(/^(AND)\b/i);
    if (keyword) {
      push("keyword", keyword[0]);
      index += keyword[0].length;
      continue;
    }

    const like = rest.match(/^(LIKE)\b/i);
    if (like) {
      push("operator", like[0]);
      index += like[0].length;
      continue;
    }

    if (rest.startsWith("=")) {
      push("operator", "=");
      index += 1;
      continue;
    }

    const string = rest.match(STRING);
    if (string) {
      push("string", string[0]);
      index += string[0].length;
      continue;
    }

    const number = rest.match(NUMBER);
    if (number) {
      push("number", number[0]);
      index += number[0].length;
      continue;
    }

    const ident = readIdentifier(input, index);
    if (ident) {
      push("column", ident);
      index += ident.length;
      continue;
    }

    push("text", rest[0]!);
    index += 1;
  }

  return tokens;
}
