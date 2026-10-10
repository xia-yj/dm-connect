import type { RpcError } from "./types";

export function rpc<T>(method: string, params: unknown = {}): Promise<T> {
  return window.dmConnect.request<T>(method, params);
}

export function asRpcError(cause: unknown): RpcError {
  return cause instanceof Error ? cause as RpcError : Object.assign(new Error(String(cause)), { code: "UNKNOWN" });
}

export function errorMessage(cause: unknown): string {
  const raw = asRpcError(cause).message || "操作失败";
  const invoke = raw.match(/^Error invoking remote method '[^']+': (?:Error: )?(.*)$/s);
  return invoke?.[1]?.trim() || raw;
}
