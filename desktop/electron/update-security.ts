export const DEFAULT_UPDATE_MANIFEST_URL =
  "https://github.com/xia-yj/dm-connect/releases/latest/download/update.json";

const ALLOWED_HOSTS = new Set(["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"]);

export interface UpdateManifest {
  version: string;
  build?: string;
  url?: string;
  arm64Url?: string;
  x64Url?: string;
  notes?: string;
  sha256?: string;
  arm64Sha256?: string;
  x64Sha256?: string;
}

export interface AvailableUpdate {
  version: string;
  build?: string;
  url: string;
  sha256: string;
  notes?: string;
}

export function requireAllowedHttpsUrl(value: string, label: string): URL {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error(`${label}不正确`);
  }
  if (url.protocol !== "https:") throw new Error(`${label}仅支持 https 地址`);
  if (!ALLOWED_HOSTS.has(url.hostname.toLowerCase())) {
    throw new Error(`${label}仅允许 GitHub Releases 地址`);
  }
  return url;
}

export function updateUrlForArchitecture(manifest: UpdateManifest, arch: string): string | undefined {
  if (arch === "arm64") return manifest.arm64Url ?? manifest.url;
  if (arch === "x64") return manifest.x64Url ?? manifest.url;
  return undefined;
}

export function updateHashForArchitecture(manifest: UpdateManifest, arch: string): string | undefined {
  if (arch === "arm64") return manifest.arm64Sha256 ?? manifest.sha256;
  if (arch === "x64") return manifest.x64Sha256 ?? manifest.sha256;
  return manifest.sha256;
}

export function parseUpdateManifest(raw: string, currentVersion: string, arch: string): AvailableUpdate | null {
  let manifest: UpdateManifest;
  try {
    manifest = JSON.parse(raw) as UpdateManifest;
  } catch {
    throw new Error("更新清单不是有效的 JSON");
  }
  if (!manifest.version || typeof manifest.version !== "string") throw new Error("更新清单缺少版本号");
  const url = updateUrlForArchitecture(manifest, arch);
  if (!url) throw new Error(`更新清单未提供 ${arch} 架构的安装包`);
  requireAllowedHttpsUrl(url, "更新包地址");
  const sha256 = normalizeSha256(updateHashForArchitecture(manifest, arch));
  if (!sha256) throw new Error("更新清单缺少对应架构的 SHA-256");
  return compareVersion(manifest.version, currentVersion) > 0
    ? { version: manifest.version, build: manifest.build, url, sha256, notes: manifest.notes }
    : null;
}

export function normalizeSha256(value: string | undefined): string | undefined {
  if (!value || typeof value !== "string") return undefined;
  const hash = value.trim().toLowerCase();
  return /^[a-f0-9]{64}$/.test(hash) ? hash : undefined;
}

export function compareVersion(left: string, right: string): number {
  const parse = (value: string) => value.split(".").map(part => Number.parseInt(part.replace(/\D.*$/, ""), 10) || 0);
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    if ((a[index] ?? 0) !== (b[index] ?? 0)) return (a[index] ?? 0) > (b[index] ?? 0) ? 1 : -1;
  }
  return 0;
}

export function timingSafeEqualHex(left: string, right: string): boolean {
  const a = Buffer.from(left, "utf8");
  const b = Buffer.from(right, "utf8");
  if (a.length !== b.length) return false;
  return require("node:crypto").timingSafeEqual(a, b);
}
