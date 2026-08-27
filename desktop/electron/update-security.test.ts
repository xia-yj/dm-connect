// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  compareVersion,
  normalizeSha256,
  parseUpdateManifest,
  requireAllowedHttpsUrl,
  timingSafeEqualHex
} from "./update-security";

const hash = "a".repeat(64);
const newer = JSON.stringify({
  version: "2.0.12",
  arm64Url: "https://github.com/xia-yj/dm-connect/releases/download/v2.0.12/DM-Connect-2.0.12-arm64.app.zip",
  arm64Sha256: hash
});

describe("update-security", () => {
  it("rejects non-https and non-github hosts", () => {
    expect(() => requireAllowedHttpsUrl("http://github.com/a", "更新清单地址")).toThrow(/https/);
    expect(() => requireAllowedHttpsUrl("https://evil.example/update.json", "更新清单地址")).toThrow(/GitHub/);
    expect(requireAllowedHttpsUrl("https://github.com/xia-yj/dm-connect/releases/latest/download/update.json", "更新清单地址").hostname)
      .toBe("github.com");
  });

  it("requires a sha256 and only returns newer versions", () => {
    expect(parseUpdateManifest(newer, "2.0.11", "arm64")?.sha256).toBe(hash);
    expect(parseUpdateManifest(newer, "2.0.12", "arm64")).toBeNull();
    expect(() => parseUpdateManifest(JSON.stringify({
      version: "2.0.12",
      arm64Url: "https://github.com/xia-yj/dm-connect/releases/download/v2.0.12/a.zip"
    }), "2.0.11", "arm64")).toThrow(/SHA-256/);
  });

  it("rejects update packages hosted outside GitHub", () => {
    expect(() => parseUpdateManifest(JSON.stringify({
      version: "2.0.12",
      arm64Url: "https://evil.example/a.zip",
      arm64Sha256: hash
    }), "2.0.11", "arm64")).toThrow(/GitHub/);
  });

  it("normalizes hashes and compares versions", () => {
    expect(normalizeSha256(` ${hash.toUpperCase()} `)).toBe(hash);
    expect(normalizeSha256("zz")).toBeUndefined();
    expect(compareVersion("2.0.11", "2.0.9")).toBe(1);
    expect(timingSafeEqualHex(hash, hash)).toBe(true);
    expect(timingSafeEqualHex(hash, "b".repeat(64))).toBe(false);
  });
});
