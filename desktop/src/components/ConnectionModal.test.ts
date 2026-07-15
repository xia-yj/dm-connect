import { describe, expect, it } from "vitest";
import { parseProperties } from "./ConnectionModal";

describe("advanced JDBC properties", () => {
  it("parses non-secret key/value pairs", () => {
    expect(parseProperties("sslMode=PREFERRED\nsocketTimeout=30000")).toEqual({ sslMode: "PREFERRED", socketTimeout: "30000" });
  });

  it.each(["user=root", "username=root", "pwd=secret", "passwd=secret", "password=secret", "keystorePassword=secret", "password1=secret", "accessToken=secret", "clientSecret=secret"])("rejects credentials in %s", property => {
    expect(() => parseProperties(property)).toThrow("用户名、密码或其他凭据不能写入高级参数");
  });

  it("rejects case-insensitive duplicate parameters", () => {
    expect(() => parseProperties("sslmode=prefer\nSSLMODE=require")).toThrow("高级参数重复");
  });
});
