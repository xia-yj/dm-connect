// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { fetchWithNodeFallback, isHttp2ProtocolError } from "./github-fetch";

function jsonResponse(status: number, body = "{}"): Response {
  return new Response(body, { status, headers: { "content-type": "application/json" } });
}

describe("github-fetch", () => {
  it("returns a successful Chromium response without falling back", async () => {
    const fallback = vi.fn();
    const response = await fetchWithNodeFallback(
      "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json",
      async () => jsonResponse(200, '{"version":"2.0.23"}'),
      fallback
    );
    expect(await response.text()).toBe('{"version":"2.0.23"}');
    expect(fallback).not.toHaveBeenCalled();
  });

  it("falls back to Node fetch when GitHub returns a non-OK status", async () => {
    const response = await fetchWithNodeFallback(
      "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json",
      async () => jsonResponse(403),
      async () => jsonResponse(200, '{"ok":true}')
    );
    expect(response.ok).toBe(true);
    expect(await response.json()).toEqual({ ok: true });
  });

  it("falls back after Chromium HTTP/2 errors", async () => {
    const response = await fetchWithNodeFallback(
      "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json",
      async () => {
        throw new Error("net::ERR_HTTP2_PROTOCOL_ERROR");
      },
      async () => jsonResponse(200, "{}")
    );
    expect(response.status).toBe(200);
    expect(isHttp2ProtocolError(new Error("net::ERR_HTTP2_PROTOCOL_ERROR"))).toBe(true);
  });

  it("keeps the fallback response when both attempts fail", async () => {
    const onFallback = vi.fn();
    const response = await fetchWithNodeFallback(
      "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json",
      async () => jsonResponse(403),
      async () => jsonResponse(404),
      onFallback
    );
    expect(response.status).toBe(404);
    expect(onFallback).toHaveBeenCalledOnce();
  });
});
