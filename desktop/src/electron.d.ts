export {};

declare global {
  interface Window {
    dmConnect: {
      request<T>(method: string, params?: unknown): Promise<T>;
      selectDriver(): Promise<string | null>;
      selectDatabaseFile(): Promise<string | null>;
      createDatabaseFile(): Promise<string | null>;
      saveCsv(defaultName: string): Promise<string | null>;
      saveSql(): Promise<string | null>;
      saveLocalSql(defaultName: string, content: string): Promise<string | null>;
      checkForUpdate(manifestUrl: string): Promise<import("./types").AppUpdateInfo | null>;
      installUpdate(manifestUrl: string): Promise<void>;
      onUpdateCheck(listener: () => void): () => void;
      onUpdateStatus(listener: (status: string) => void): () => void;
      platform: string;
    };
  }
}
