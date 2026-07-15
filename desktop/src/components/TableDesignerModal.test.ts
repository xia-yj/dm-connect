import { describe, expect, it } from "vitest";
import { supportedTableTypes } from "./TableDesignerModal";

describe("table designer type routing", () => {
  it("keeps DM-specific types on DM profiles", () => {
    expect(supportedTableTypes("dm").has("BFILE")).toBe(true);
    expect(supportedTableTypes("dm").has("MEDIUMINT")).toBe(false);
  });

  it("exposes MySQL JSON, spatial, medium integer and LOB types", () => {
    const types = supportedTableTypes("mysql");
    expect(types.has("MEDIUMINT")).toBe(true);
    expect(types.has("JSON")).toBe(true);
    expect(types.has("GEOMETRYCOLLECTION")).toBe(true);
    expect(types.has("LONGBLOB")).toBe(true);
    expect(types.has("BFILE")).toBe(false);
  });
});
