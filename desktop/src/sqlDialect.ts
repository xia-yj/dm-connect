import type { DatabaseType } from "./types";

export interface SqlSnippet {
  label: string;
  detail: string;
  insertText: string;
}

export interface SqlDialectConfig {
  label: string;
  namespaceLabel: string;
  keywords: string[];
  functions: string[];
  snippets: SqlSnippet[];
}

const COMMON_KEYWORDS = [
  "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "ON",
  "GROUP BY", "ORDER BY", "HAVING", "INSERT INTO", "VALUES", "UPDATE", "SET",
  "DELETE FROM", "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "WITH", "UNION",
  "DISTINCT", "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "LIMIT", "OFFSET",
  "COMMIT", "ROLLBACK"
];

const COMMON_FUNCTIONS = [
  "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "NULLIF", "CAST", "LENGTH",
  "LOWER", "UPPER", "CURRENT_TIMESTAMP"
];

const COMMON_SNIPPETS: SqlSnippet[] = [
  { label: "sel", detail: "标准查询", insertText: "SELECT * FROM" },
  { label: "selw", detail: "带条件查询", insertText: "SELECT ${1:*}\nFROM ${2:table_name}\nWHERE ${3:condition};" },
  { label: "ins", detail: "插入数据", insertText: "INSERT INTO ${1:table_name} (${2:column_name})\nVALUES (${3:value});" },
  { label: "upd", detail: "更新数据", insertText: "UPDATE ${1:table_name}\nSET ${2:column_name} = ${3:value}\nWHERE ${4:condition};" },
  { label: "del", detail: "删除数据", insertText: "DELETE FROM ${1:table_name}\nWHERE ${2:condition};" },
  { label: "ct", detail: "创建表", insertText: "CREATE TABLE ${1:table_name} (\n  ${2:id} ${3:BIGINT} PRIMARY KEY\n);" }
];

const DIALECTS: Record<DatabaseType, Omit<SqlDialectConfig, "keywords" | "functions" | "snippets"> & {
  keywords: string[];
  functions: string[];
  snippets: SqlSnippet[];
}> = {
  dm: {
    label: "DM SQL",
    namespaceLabel: "数据库模式",
    keywords: ["MERGE INTO", "CONNECT BY", "START WITH", "CREATE OR REPLACE", "IDENTITY"],
    functions: ["NVL", "SUBSTR", "SYSDATE", "SYSTIMESTAMP", "DECODE", "TO_CHAR", "TO_DATE", "ADD_MONTHS", "LISTAGG"],
    snippets: []
  },
  mysql: {
    label: "MySQL SQL",
    namespaceLabel: "数据库",
    keywords: ["SHOW", "DESCRIBE", "EXPLAIN", "USE", "REPLACE INTO", "ON DUPLICATE KEY UPDATE", "AUTO_INCREMENT", "ENGINE"],
    functions: ["IFNULL", "SUBSTRING", "NOW", "CURDATE", "CURTIME", "DATE_FORMAT", "DATE_ADD", "GROUP_CONCAT", "JSON_EXTRACT", "CONCAT", "LAST_INSERT_ID"],
    snippets: [
      {
        label: "ctai",
        detail: "MySQL 自增主键表",
        insertText: "CREATE TABLE ${1:table_name} (\n  ${2:id} BIGINT AUTO_INCREMENT PRIMARY KEY\n) ENGINE=InnoDB;"
      },
      {
        label: "upsert",
        detail: "MySQL 插入或更新",
        insertText: "INSERT INTO ${1:table_name} (${2:column_name})\nVALUES (${3:value}) AS new\nON DUPLICATE KEY UPDATE ${2:column_name} = new.${2:column_name};"
      }
    ]
  }
};

export function sqlDialectConfig(databaseType: DatabaseType): SqlDialectConfig {
  const dialect = DIALECTS[databaseType];
  return {
    ...dialect,
    keywords: [...COMMON_KEYWORDS, ...dialect.keywords],
    functions: [...COMMON_FUNCTIONS, ...dialect.functions],
    snippets: [...COMMON_SNIPPETS, ...dialect.snippets]
  };
}
