import { contextBridge, ipcRenderer } from "electron";

interface BackendEnvelope<T> {
  ok: boolean;
  value?: T;
  error?: { code: string; message: string; data?: unknown };
}

contextBridge.exposeInMainWorld("dmConnect", {
  request: async <T>(method: string, params: unknown = {}): Promise<T> => {
    const response = await ipcRenderer.invoke("backend:request", method, params) as BackendEnvelope<T>;
    if (response.ok) return response.value as T;
    const error = Object.assign(new Error(response.error?.message ?? "后端请求失败"), {
      code: response.error?.code ?? "BACKEND_ERROR",
      data: response.error?.data
    });
    throw error;
  },
  selectDriver: (): Promise<string | null> => ipcRenderer.invoke("dialog:select-driver"),
  saveCsv: (defaultName: string): Promise<string | null> => ipcRenderer.invoke("dialog:save-csv", defaultName),
  saveSql: (): Promise<string | null> => ipcRenderer.invoke("dialog:save-sql"),
  checkForUpdate: (manifestUrl: string) => ipcRenderer.invoke("update:check", manifestUrl),
  installUpdate: (manifestUrl: string) => ipcRenderer.invoke("update:install", manifestUrl),
  onUpdateCheck: (listener: () => void) => {
    const handler = () => listener();
    ipcRenderer.on("update:check", handler);
    return () => ipcRenderer.removeListener("update:check", handler);
  },
  onUpdateStatus: (listener: (status: string) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, status: string) => listener(status);
    ipcRenderer.on("update:status", handler);
    return () => ipcRenderer.removeListener("update:status", handler);
  },
  platform: process.platform
});
