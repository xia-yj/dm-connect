type FetchFn = (input: string) => Promise<Response>;

export function isHttp2ProtocolError(cause: unknown): boolean {
  return String(cause).includes("ERR_HTTP2_PROTOCOL_ERROR");
}

export async function fetchWithNodeFallback(
  url: string,
  primaryFetch: FetchFn,
  fallbackFetch: FetchFn = input => globalThis.fetch(input),
  onFallback?: () => void
): Promise<Response> {
  try {
    const response = await primaryFetch(url);
    if (response.ok) return response;
  } catch (cause) {
    if (!isRetryableGithubFetchError(cause)) throw cause;
  }
  onFallback?.();
  return fallbackFetch(url);
}

function isRetryableGithubFetchError(cause: unknown): boolean {
  const text = String(cause);
  return /ERR_HTTP2_|ERR_QUIC_|ERR_CONNECTION_|ERR_NETWORK_|ERR_TIMED_OUT|ERR_FAILED|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN/.test(text);
}
