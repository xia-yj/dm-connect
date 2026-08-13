import { describe, expect, it } from "vitest";
import { tokenizePreviewFilter } from "./previewFilterHighlight";

describe("tokenizePreviewFilter", () => {
  it("highlights columns, operators, strings, numbers and AND", () => {
    const tokens = tokenizePreviewFilter("ID = 1 AND NAME LIKE '%大的%'");
    expect(tokens.map(token => [token.kind, token.text])).toEqual([
      ["column", "ID"],
      ["text", " "],
      ["operator", "="],
      ["text", " "],
      ["number", "1"],
      ["text", " "],
      ["keyword", "AND"],
      ["text", " "],
      ["column", "NAME"],
      ["text", " "],
      ["operator", "LIKE"],
      ["text", " "],
      ["string", "'%大的%'"]
    ]);
  });

  it("keeps incomplete quotes as string tokens while typing", () => {
    const tokens = tokenizePreviewFilter("TITLE LIKE '大");
    expect(tokens.map(token => token.kind)).toEqual(["column", "text", "operator", "text", "string"]);
    expect(tokens.at(-1)?.text).toBe("'大");
  });

  it("supports quoted-looking CJK column names as identifiers", () => {
    const tokens = tokenizePreviewFilter("风险级别 = '高危'");
    expect(tokens.map(token => [token.kind, token.text])).toEqual([
      ["column", "风险级别"],
      ["text", " "],
      ["operator", "="],
      ["text", " "],
      ["string", "'高危'"]
    ]);
  });
});
