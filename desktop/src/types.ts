export interface DriverDescriptor {
  id: string;
  displayName: string;
  sha256: string;
  driverClass: string;
  version: string;
  importedAt: string;
}

export interface ConnectionProfile {
  id: string;
  name: string;
  databaseType: "dm";
  host: string;
  port: number;
  username: string;
  driverId: string;
  advancedProperties: Record<string, string>;
  rememberPassword: boolean;
  hasSavedPassword: boolean;
  connected: boolean;
}

export interface ProfileDraft {
  id?: string;
  name: string;
  host: string;
  port: number;
  username: string;
  password: string;
  driverId: string;
  advancedProperties: Record<string, string>;
  rememberPassword: boolean;
}

export interface BootstrapData {
  version: string;
  profiles: ConnectionProfile[];
  drivers: DriverDescriptor[];
  connectedProfileIds: string[];
  legacyVaultBackup?: string;
}

export interface AppUpdateInfo {
  version: string;
  build?: string;
  url: string;
  notes?: string;
}

export type DatabaseObjectKind = "TABLE" | "VIEW" | "SEQUENCE" | "PROCEDURE" | "FUNCTION" | "TRIGGER";

export interface DatabaseObject {
  schema: string;
  name: string;
  kind: DatabaseObjectKind;
  remarks: string;
}

export interface ColumnMetadata {
  name: string;
  label: string;
  typeName: string;
  jdbcType: number;
  nullable: boolean;
  remarks?: string | null;
}

export interface ResultTable {
  columns: ColumnMetadata[];
  rows: unknown[][];
  truncated: boolean;
}

export interface PagedResultTable extends ResultTable {
  totalRows: number;
  page: number;
  pageSize: number;
}

export interface ColumnInfo {
  ordinal: number;
  name: string;
  typeName: string;
  size: number;
  scale: number;
  nullable: boolean;
  defaultValue: string | null;
  autoIncrement: boolean;
  remarks: string | null;
}

export interface ConstraintInfo {
  name: string;
  type: string;
  columns: string[];
  referencedSchema: string | null;
  referencedTable: string | null;
  referencedColumns: string[];
}

export interface IndexInfo {
  name: string;
  unique: boolean;
  columns: string[];
}

export interface TableDetails {
  table: DatabaseObject;
  columns: ColumnInfo[];
  constraints: ConstraintInfo[];
  indexes: IndexInfo[];
}

export interface TableColumnDraft {
  originalName: string | null;
  name: string;
  type: "VARCHAR" | "CHAR" | "INT" | "BIGINT" | "DECIMAL" | "DATE" | "TIME" | "TIMESTAMP" | "CLOB" | "BLOB";
  length: number | null;
  scale: number | null;
  nullable: boolean;
  primaryKey: boolean;
  autoIncrement: boolean;
  defaultExpression: string | null;
  remark: string | null;
}

export interface TableDefinitionDraft {
  schema: string;
  name: string;
  columns: TableColumnDraft[];
  primaryKeyName: string | null;
}

export interface ObjectLoadResult {
  object: DatabaseObject;
  details: TableDetails | null;
  detailsError: string;
  preview: PagedResultTable | null;
  previewError: string;
  ddl: string | null;
  ddlError: string;
}

export interface QueryOpenResult {
  sessionId: string;
  profileId: string;
  autoCommit: boolean;
  pendingTransaction: boolean;
}

export interface QueryStatus extends QueryOpenResult {}

export interface StatementOutcome {
  statementIndex: number;
  resultIndex: number;
  resultId: string | null;
  table: ResultTable | null;
  updateCount: number | null;
}

export interface ExecutionResult {
  outcomes: StatementOutcome[];
  success: boolean;
  errorMessage: string;
  sqlState: string;
  vendorCode: number;
  durationMillis: number;
  executedStatements: number;
  historyWarning: string;
  autoCommit: boolean;
  pendingTransaction: boolean;
}

export interface HistoryEntry {
  id: string;
  profileId: string;
  profileName: string;
  executedAt: string;
  success: boolean;
  durationMillis: number;
  sql: string;
}

export interface RpcError extends Error {
  code?: string;
  data?: unknown;
}

export type WorkspaceTab =
  | { id: "welcome"; type: "welcome"; title: string }
  | { id: string; type: "object"; title: string; profileId: string; result: ObjectLoadResult }
  | { id: string; type: "sql"; title: string; profileId: string; profileName: string; sessionId: string; initialSql: string };
