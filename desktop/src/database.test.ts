import { describe, expect, it } from "vitest";
import { databaseTypeLabel, driverDatabaseType, jdbcUrlPreview } from "./database";
import type { DriverDescriptor } from "./types";

function driver(driverClass: string, databaseType?: "dm" | "mysql"): DriverDescriptor {
  return { id: "1", displayName: "driver.jar", sha256: "abc", driverClass, version: "1.0", importedAt: "2026-01-01", databaseType };
}

describe("database helpers", () => {
  it("recognizes DM and both MySQL Connector/J class names", () => {
    expect(driverDatabaseType(driver("dm.jdbc.driver.DmDriver"))).toBe("dm");
    expect(driverDatabaseType(driver("com.mysql.cj.jdbc.Driver"))).toBe("mysql");
    expect(driverDatabaseType(driver("com.mysql.jdbc.Driver"))).toBe("mysql");
  });

  it("prefers the database type reported by the backend", () => {
    expect(driverDatabaseType(driver("vendor.Driver", "mysql"))).toBe("mysql");
  });

  it("builds engine-specific JDBC URL previews", () => {
    expect(jdbcUrlPreview("dm", "db.local", 5236)).toBe("jdbc:dm://db.local:5236");
    expect(jdbcUrlPreview("mysql", "db.local", 3306, "app data")).toBe("jdbc:mysql://db.local:3306/app%20data");
  });

  it("labels each supported database type", () => {
    expect(databaseTypeLabel("dm")).toBe("DM");
    expect(databaseTypeLabel("mysql")).toBe("MySQL");
  });
});
