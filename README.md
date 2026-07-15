# DM Connect 2.0

DM Connect 是面向达梦数据库 DBA 的 macOS 桌面工具。2.0 版已迁移为 **React + Electron + Java 17 后端**：界面使用 React，JDBC、对象元数据、SQL 执行、事务、本地加密存储和 CSV 核心由 Java 负责。

## 架构

```text
React 渲染进程（无 Node.js / 无文件系统权限）
        ↕ 受限 contextBridge API
Electron 主进程（校验页面来源与方法白名单）
        ↕ stdin/stdout 单行 JSON RPC（不开放网络端口）
Java 17 后端（JDBC / 元数据 / SQL / 事务 / 本地加密存储 / CSV）
        ↕ 独立 URLClassLoader
用户导入的达梦 JDBC JAR
```

支持功能：

- 新建、编辑、复制、删除、测试和断开达梦连接。
- 懒加载模式、表、视图、序列、过程、函数和触发器。
- 双击表名默认展示最多 200 行数据预览，同时可查看列、约束、索引和 DDL。
- Monaco SQL 多标签工作台，每个标签拥有独立 JDBC 会话和事务状态。
- 执行选中内容、当前语句或整个脚本，可取消执行，支持多结果集。
- `DROP` / `TRUNCATE` 二次确认，未提交标签关闭确认。
- 应用自动管理本机密钥，使用 Argon2id + AES-256-GCM 保存密码和最近 1000 条 SQL 历史。
- 将当前已加载结果导出为 UTF-8 BOM CSV，不重新执行查询。

## 开发环境

- macOS arm64（Apple Silicon）
- JDK 17
- Maven 3.9+
- Node.js 22+
- 与目标达梦服务器版本相匹配的官方 JDBC JAR

应用不随包分发达梦 JDBC 驱动。可从达梦安装目录的 `drivers/jdbc` 获取，然后在应用中点击“导入 JAR”。驱动类为 `dm.jdbc.driver.DmDriver`，默认连接地址为 `jdbc:dm://host:5236`。

## 本地运行

```bash
./scripts/run.sh
```

脚本会编译 Java 后端、生成精简 Java Runtime，再启动 Vite 和 Electron。应用启动后直接进入工作台，无需创建或输入主密码。数据库本身的用户名和密码仍在建立连接时使用。

本地数据位于：

```text
~/Library/Application Support/DM Connect/
├── profiles.json       # 不含密码的连接信息
├── drivers.json        # 驱动摘要、版本和 SHA-256
├── drivers/            # 导入的 JDBC JAR 副本
├── vault.key           # 应用自动生成的本机密钥（仅当前用户可读写）
└── vault.json          # AES-256-GCM 加密的密码与 SQL 历史
```

从使用主密码的旧版升级时，应用无法在不询问主密码的情况下解密旧数据，因此会将旧文件保留为 `vault.master-password-backup.json`，然后创建新的本机加密存储。连接配置会保留，已保存的数据库密码需要重新输入。

## SQL 脚本规则

普通语句使用分号分隔。过程、函数、触发器和匿名块使用独占一行的 `/` 结束：

```sql
CREATE OR REPLACE PROCEDURE P_TEST AS
BEGIN
  INSERT INTO T VALUES (1);
END;
/
```

## 测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn verify

source scripts/node-env.sh
dmconnect_use_node
cd desktop
npm test
npm run build
```

真实达梦集成测试使用 `DM_TEST_HOST`、`DM_TEST_PORT`、`DM_TEST_USER`、`DM_TEST_PASSWORD`、`DM_TEST_SCHEMA` 和 `DM_JDBC_JAR` 注入；未配置时自动跳过。

## 构建 macOS DMG

```bash
./scripts/package-macos.sh
```

构建脚本会执行 Java 和 React 测试、生成内置精简 Java Runtime 的 Electron `.app`，并对 `.app` 和 `.dmg` 做 ad-hoc 签名。产物位于 `target/installer/`，包括 DMG 和用于自动更新的 `.app.zip`。本版未做 Apple 公证。

## 自动更新发布

应用默认从同一更新服务器读取：

```text
http://10.20.25.68:8093/dm-connect-updates/update.json
```

也可以在应用“设置 → 应用更新”中改成其他地址。更新方式与同服务器的 mac-app 一致：应用下载包含完整 `.app` 的 ZIP，退出后由临时脚本替换旧应用并重新启动，因此首次仍应手动安装 DMG。

每次发布时，在 Apple Silicon Mac 和 Intel Mac 分别运行：

```bash
./scripts/package-macos.sh
```

分别得到 `DM-Connect-<版本>-arm64.app.zip` 与 `DM-Connect-<版本>-x64.app.zip`。上传两份 ZIP 和下面的清单到服务器的 `/opt/dm-connect-updates/`：

```json
{
  "version": "2.1.0",
  "build": "1",
  "arm64Url": "http://10.20.25.68:8093/dm-connect-updates/DM-Connect-2.1.0-arm64.app.zip",
  "x64Url": "http://10.20.25.68:8093/dm-connect-updates/DM-Connect-2.1.0-x64.app.zip",
  "notes": "本次更新说明"
}
```

Nginx 在现有 8093 站点中增加：

```nginx
location /dm-connect-updates/ {
    alias /opt/dm-connect-updates/;
    autoindex off;
    add_header Cache-Control "no-cache";
    types {
        application/json json;
        application/zip zip;
    }
}
```

## 当前边界

- 仅支持达梦数据库和中文界面。
- 不包含用户权限管理、会话监控、备份恢复、表空间管理、可视化数据编辑、数据导入、Excel 导出、SSH 隧道和证书向导。
- SSL 等达梦 JDBC 属性通过连接编辑器的“高级 JDBC 参数”传入。
