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
  }
};

export function databaseTypeLabel(databaseType: DatabaseType): "DM" | "MySQL" {
  return databaseType === "mysql" ? "MySQL" : "DM";
}

export function driverDatabaseType(driver: DriverDescriptor): DatabaseType | null {
  if (driver.databaseType === "dm" || driver.databaseType === "mysql") return driver.databaseType;
  const className = driver.driverClass.toLowerCase();
  if (className === "dm.jdbc.driver.dmdriver") return "dm";
  if (className === "com.mysql.cj.jdbc.driver" || className === "com.mysql.jdbc.driver") return "mysql";
  return null;
}

export function jdbcUrlPreview(databaseType: DatabaseType, host: string, port: number, database = ""): string {
  const targetHost = host.trim() || "localhost";
  return databaseType === "mysql"
    ? `jdbc:mysql://${targetHost}:${port || DATABASE_INFO.mysql.defaultPort}/${encodeURIComponent(database.trim())}`
    : `jdbc:dm://${targetHost}:${port || DATABASE_INFO.dm.defaultPort}`;
}
