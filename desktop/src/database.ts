import type { DatabaseType, DriverDescriptor } from "./types";

export const DATABASE_INFO: Record<DatabaseType, {
  label: string;
  shortLabel: string;
  defaultPort: number;
  driverHint: string;
  advancedPlaceholder: string;
}> = {
  dm: {
    label: "达梦数据库",
    shortLabel: "DM",
    defaultPort: 5236,
    driverHint: "请导入与服务器版本匹配的达梦 JDBC 驱动（DmJdbcDriver*.jar）",
    advancedPlaceholder: "socketTimeout=30000\nssl=true"
  },
  mysql: {
    label: "MySQL",
    shortLabel: "MySQL",
    defaultPort: 3306,
    driverHint: "已内置 MySQL Connector/J 8.3；如需切换版本，可继续导入其他 Connector/J JAR",
    advancedPlaceholder: "sslMode=PREFERRED\nconnectTimeout=10000\nsocketTimeout=30000"
  },
  mongo: { label: "MongoDB", shortLabel: "MongoDB", defaultPort: 27017, driverHint: "应用内置 MongoDB 原生客户端", advancedPlaceholder: "authSource=admin\ntls=false\nsocketTimeoutMS=30000" },
  redis: { label: "Redis", shortLabel: "Redis", defaultPort: 6379, driverHint: "应用内置 Redis 原生客户端", advancedPlaceholder: "database=0\ntls=false\ncommandTimeoutMS=10000" },
  postgresql: { label: "PostgreSQL", shortLabel: "PostgreSQL", defaultPort: 5432, driverHint: "已内置 PostgreSQL JDBC 驱动", advancedPlaceholder: "sslmode=prefer" },
  oracle: { label: "Oracle", shortLabel: "Oracle", defaultPort: 1521, driverHint: "请导入与服务器和 Java 版本匹配的 ojdbc JAR", advancedPlaceholder: "oracle.net.CONNECT_TIMEOUT=10000" },
  sqlserver: { label: "SQL Server", shortLabel: "SQL Server", defaultPort: 1433, driverHint: "已内置 Microsoft SQL Server JDBC 驱动", advancedPlaceholder: "encrypt=true\ntrustServerCertificate=false" },
  sqlite: { label: "SQLite", shortLabel: "SQLite", defaultPort: 1, driverHint: "已内置 SQLite JDBC 驱动", advancedPlaceholder: "" }
};

export function isNativeDatabase(databaseType: DatabaseType): boolean {
  return databaseType === "mongo" || databaseType === "redis";
}

export function isJdbcDatabase(databaseType: DatabaseType): boolean {
  return !isNativeDatabase(databaseType);
}

export function supportsTableDesigner(databaseType: DatabaseType): boolean {
  return databaseType === "dm" || databaseType === "mysql";
}

export function connectionSummary(profile: { databaseType: DatabaseType; host: string; port: number; database: string; username: string; advancedProperties: Record<string, string> }): string {
  if (profile.databaseType === "sqlite") return profile.database || "未选择数据库文件";
  const identity = profile.username ? `${profile.username}@` : "";
  if (profile.databaseType === "redis") {
    const database = Object.entries(profile.advancedProperties).find(([key]) => key.toLowerCase() === "database")?.[1] ?? "0";
    return `${identity}${profile.host}:${profile.port} · db${database}`;
  }
  return `${identity}${profile.host}:${profile.port}`;
}

export function databaseTypeLabel(databaseType: DatabaseType): string {
  return DATABASE_INFO[databaseType].shortLabel;
}

export function driverDatabaseType(driver: DriverDescriptor): DatabaseType | null {
  if (driver.databaseType && driver.databaseType in DATABASE_INFO) return driver.databaseType;
  const className = driver.driverClass.toLowerCase();
  if (className === "dm.jdbc.driver.dmdriver") return "dm";
  if (className === "com.mysql.cj.jdbc.driver" || className === "com.mysql.jdbc.driver") return "mysql";
  if (className === "org.postgresql.driver") return "postgresql";
  if (className === "oracle.jdbc.oracledriver") return "oracle";
  if (className === "com.microsoft.sqlserver.jdbc.sqlserverdriver") return "sqlserver";
  if (className === "org.sqlite.jdbc") return "sqlite";
  return null;
}

export function jdbcUrlPreview(databaseType: DatabaseType, host: string, port: number, database = ""): string {
  const targetHost = host.trim() || "localhost";
  const urlHost = targetHost.includes(":") && !(targetHost.startsWith("[") && targetHost.endsWith("]")) ? `[${targetHost}]` : targetHost;
  const targetDatabase = database.trim();
  if (databaseType === "mongo") return `mongodb://${urlHost}:${port || DATABASE_INFO.mongo.defaultPort}/${encodeURIComponent(targetDatabase || "admin")}`;
  if (databaseType === "redis") return `redis://${urlHost}:${port || DATABASE_INFO.redis.defaultPort}`;
  if (databaseType === "sqlite") return `jdbc:sqlite:${targetDatabase || "/path/to/database.db"}`;
  if (databaseType === "mysql") return `jdbc:mysql://${urlHost}:${port || DATABASE_INFO.mysql.defaultPort}/${encodeURIComponent(targetDatabase)}`;
  if (databaseType === "postgresql") return `jdbc:postgresql://${urlHost}:${port || DATABASE_INFO.postgresql.defaultPort}/${encodeURIComponent(targetDatabase)}`;
  if (databaseType === "oracle") return `jdbc:oracle:thin:@//${urlHost}:${port || DATABASE_INFO.oracle.defaultPort}/${targetDatabase || "service_name"}`;
  if (databaseType === "sqlserver") {
    const value = /^[A-Za-z0-9._-]+$/.test(targetDatabase) ? targetDatabase : `{${targetDatabase.replace(/}/g, "}}")}}`;
    return `jdbc:sqlserver://${urlHost}:${port || DATABASE_INFO.sqlserver.defaultPort};databaseName=${value}`;
  }
  return `jdbc:dm://${urlHost}:${port || DATABASE_INFO.dm.defaultPort}`;
}
