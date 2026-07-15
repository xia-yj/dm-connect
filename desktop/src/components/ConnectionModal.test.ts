import { describe, expect, it } from "vitest";
import { parseProperties } from "./ConnectionModal";

describe("advanced JDBC properties", () => {
  it("parses non-secret key/value pairs", () => {
    expect(parseProperties("sslMode=PREFERRED\nsocketTimeout=30000")).toEqual({ sslMode: "PREFERRED", socketTimeout: "30000" });
  });

  it.each(["user=root", "pwd=secret", "passwd=secret", "password=secret", "keystorePassword=secret", "password1=secret"])("rejects credentials in %s", property => {
    expect(() => parseProperties(property)).toThrow("用户名和密码不能写入高级参数");
  });
});
