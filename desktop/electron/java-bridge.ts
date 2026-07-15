import { ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import { app } from "electron";
import { delimiter, join } from "node:path";
import { createInterface } from "node:readline";
import { existsSync } from "node:fs";

export interface BridgeError extends Error {
  code: string;
  data?: unknown;
}

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (error: BridgeError) => void;
  timer: NodeJS.Timeout;
}

interface BackendResponse {
  id: string;
  result?: unknown;
  error?: { code?: string; message?: string; data?: unknown };
}

export class JavaBridge {
  private child?: ChildProcessWithoutNullStreams;
  private sequence = 0;
  private readonly pending = new Map<string, PendingCall>();

  start(): void {
    if (this.child) return;
    const backendRoot = app.isPackaged
      ? join(process.resourcesPath, "backend")
      : join(app.getAppPath(), "..", "target", "electron-backend");
    const javaExecutable = join(backendRoot, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
    const appJar = join(backendRoot, "app", "dm-connect.jar");
    const libraries = join(backendRoot, "app", "lib", "*");
    if (!existsSync(javaExecutable) || !existsSync(appJar)) {
      throw new Error(`Java 后端资源不存在：${backendRoot}`);
    }
    const javaOptions = ["-Dfile.encoding=UTF-8"];
    if (process.env.DMCONNECT_DATA_DIR) {
      javaOptions.push(`-Ddmconnect.dataDir=${process.env.DMCONNECT_DATA_DIR}`);
    }
    this.child = spawn(javaExecutable, [
      ...javaOptions,
      "-cp",
      `${appJar}${delimiter}${libraries}`,
      "com.dmconnect.backend.BackendLauncher"
    ], {
      stdio: ["pipe", "pipe", "pipe"],
      env: { ...process.env, JAVA_TOOL_OPTIONS: "" }
    });

    createInterface({ input: this.child.stdout }).on("line", line => this.onLine(line));
    createInterface({ input: this.child.stderr }).on("line", line => {
      if (!app.isPackaged) console.error(`[java] ${line}`);
    });
    this.child.on("exit", (code, signal) => {
      const error = this.error("BACKEND_EXITED", `Java 后端已退出（${code ?? signal ?? "unknown"}）`);
      this.pending.forEach(call => {
        clearTimeout(call.timer);
        call.reject(error);
      });
      this.pending.clear();
      this.child = undefined;
    });
    this.child.on("error", cause => {
      const error = this.error("BACKEND_START_FAILED", cause.message);
      this.pending.forEach(call => call.reject(error));
      this.pending.clear();
    });
  }

  request<T>(method: string, params: unknown = {}): Promise<T> {
    if (!this.child || this.child.killed) return Promise.reject(this.error("BACKEND_UNAVAILABLE", "Java 后端尚未启动"));
    const id = String(++this.sequence);
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(this.error("BACKEND_TIMEOUT", `后端请求超时：${method}`));
      }, method === "query.execute" ? 3_600_000 : 90_000);
      this.pending.set(id, { resolve: value => resolve(value as T), reject, timer });
      this.child?.stdin.write(`${JSON.stringify({ id, method, params })}\n`, "utf8", cause => {
        if (!cause) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(this.error("BACKEND_WRITE_FAILED", cause.message));
      });
    });
  }

  close(): void {
    if (!this.child) return;
    this.child.stdin.end();
    const child = this.child;
    const timer = setTimeout(() => child.kill("SIGTERM"), 1500);
    child.once("exit", () => clearTimeout(timer));
    this.child = undefined;
  }

  private onLine(line: string): void {
    let response: BackendResponse;
    try {
      response = JSON.parse(line) as BackendResponse;
    } catch {
      if (!app.isPackaged) console.error(`[java:invalid-json] ${line}`);
      return;
    }
    const call = this.pending.get(response.id);
    if (!call) return;
    this.pending.delete(response.id);
    clearTimeout(call.timer);
    if (response.error) {
      const error = this.error(response.error.code ?? "BACKEND_ERROR", response.error.message ?? "后端请求失败");
      error.data = response.error.data;
      call.reject(error);
    } else {
      call.resolve(response.result);
    }
  }

  private error(code: string, message: string): BridgeError {
    return Object.assign(new Error(message), { code }) as BridgeError;
  }
}
