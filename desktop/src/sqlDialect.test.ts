import { describe, expect, it } from "vitest";
import { sqlDialectConfig } from "./sqlDialect";

describe("SQL dialect completion configuration", () => {
  it("keeps DM-only functions and labels on DM sessions", () => {
    const config = sqlDialectConfig("dm");
    expect(config.label).toBe("DM SQL");
    expect(config.namespaceLabel).toBe("数据库模式");
    expect(config.functions).toContain("NVL");
    expect(config.functions).toContain("SYSDATE");
    expect(config.functions).not.toContain("IFNULL");
  });

  it("provides MySQL functions, keywords and snippets on MySQL sessions", () => {
    const config = sqlDialectConfig("mysql");
    expect(config.label).toBe("MySQL SQL");
    expect(config.namespaceLabel).toBe("数据库");
    expect(config.functions).toContain("IFNULL");
    expect(config.functions).toContain("JSON_EXTRACT");
    expect(config.functions).not.toContain("NVL");
    expect(config.keywords).toContain("ON DUPLICATE KEY UPDATE");
    expect(config.snippets.some(snippet => snippet.label === "upsert")).toBe(true);
  });
});
