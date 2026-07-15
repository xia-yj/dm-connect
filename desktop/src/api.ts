import type { RpcError } from "./types";

export function rpc<T>(method: string, params: unknown = {}): Promise<T> {
  return window.dmConnect.request<T>(method, params);
}

export function asRpcError(cause: unknown): RpcError {
  return cause instanceof Error ? cause as RpcError : Object.assign(new Error(String(cause)), { code: "UNKNOWN" });
}

export function errorMessage(cause: unknown): string {
  return asRpcError(cause).message || "操作失败";
}
