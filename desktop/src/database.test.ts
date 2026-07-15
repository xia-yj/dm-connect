import { describe, expect, it } from "vitest";
import { connectionSummary, databaseTypeLabel, driverDatabaseType, isJdbcDatabase, isNativeDatabase, jdbcUrlPreview, supportsTableDesigner } from "./database";
import type { DatabaseType, DriverDescriptor } from "./types";

function driver(driverClass: string, databaseType?: DatabaseType): DriverDescriptor {
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
    expect(jdbcUrlPreview("postgresql", "db.local", 5432, "app")).toBe("jdbc:postgresql://db.local:5432/app");
    expect(jdbcUrlPreview("oracle", "db.local", 1521, "ORCLPDB1")).toBe("jdbc:oracle:thin:@//db.local:1521/ORCLPDB1");
    expect(jdbcUrlPreview("sqlserver", "db.local", 1433, "app")).toBe("jdbc:sqlserver://db.local:1433;databaseName=app");
    expect(jdbcUrlPreview("sqlite", "", 1, "/tmp/app.db")).toBe("jdbc:sqlite:/tmp/app.db");
    expect(jdbcUrlPreview("mongo", "db.local", 27017, "app")).toBe("mongodb://db.local:27017/app");
    expect(jdbcUrlPreview("redis", "db.local", 6379)).toBe("redis://db.local:6379");
    expect(jdbcUrlPreview("postgresql", "2001:db8::1", 5432, "app data")).toBe("jdbc:postgresql://[2001:db8::1]:5432/app%20data");
    expect(jdbcUrlPreview("sqlserver", "db.local", 1433, "app;readonly=true")).toBe("jdbc:sqlserver://db.local:1433;databaseName={app;readonly=true}");
  });

  it("labels each supported database type", () => {
    expect(databaseTypeLabel("dm")).toBe("DM");
    expect(databaseTypeLabel("mysql")).toBe("MySQL");
    expect(databaseTypeLabel("postgresql")).toBe("PostgreSQL");
  });

  it("separates native clients from JDBC databases", () => {
    expect(isNativeDatabase("mongo")).toBe(true);
    expect(isNativeDatabase("redis")).toBe(true);
    expect(isJdbcDatabase("postgresql")).toBe(true);
    expect(isJdbcDatabase("sqlite")).toBe(true);
    expect(supportsTableDesigner("dm")).toBe(true);
    expect(supportsTableDesigner("mysql")).toBe(true);
    expect(supportsTableDesigner("postgresql")).toBe(false);
  });

  it("formats native and file connection summaries without fake host credentials", () => {
    expect(connectionSummary({ databaseType: "sqlite", host: "localhost", port: 1, database: "/tmp/app.db", username: "", advancedProperties: {} })).toBe("/tmp/app.db");
    expect(connectionSummary({ databaseType: "redis", host: "cache.local", port: 6379, database: "", username: "", advancedProperties: { Database: "3" } })).toBe("cache.local:6379 · db3");
    expect(connectionSummary({ databaseType: "mongo", host: "mongo.local", port: 27017, database: "app", username: "alice", advancedProperties: {} })).toBe("alice@mongo.local:27017");
  });
});
