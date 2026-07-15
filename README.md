# DM Connect 2.0

DM Connect 是支持 **达梦数据库（DM）与 MySQL** 的 macOS 数据库桌面工具。2.0 版使用 **React + Electron + Java 17 后端**：界面使用 React，JDBC、对象元数据、DDL、SQL 执行、事务、本地加密存储和数据导出核心由 Java 负责。

## 架构

```text
React 渲染进程（无 Node.js / 无文件系统权限）
        ↕ 受限 contextBridge API
Electron 主进程（校验页面来源与方法白名单）
        ↕ stdin/stdout 单行 JSON RPC（不开放网络端口）
Java 17 后端（JDBC / 元数据 / SQL / 事务 / 本地加密存储 / CSV）
        ↕ 独立 URLClassLoader
应用内置 MySQL Connector/J；用户按数据库类型导入的 DM / 其他 MySQL JDBC JAR
```

支持功能：

- 新建、编辑、复制、删除、测试和断开 DM / MySQL 连接，并按连接类型隔离 JDBC 驱动。
- 懒加载 DM 模式或 MySQL 数据库，以及表、视图、过程、函数和触发器；DM 连接同时支持序列。
- 双击表名默认展示最多 100 行数据预览，同时可查看列、约束、索引和 DDL。
- 按数据库类型创建和修改表，提供 DM / MySQL 对应的字段类型、DDL 与安全校验。
- Monaco SQL 多标签工作台，每个标签拥有独立 JDBC 会话、事务状态和方言提示。
- 执行选中内容、当前语句或整个脚本，可取消执行，支持多结果集。
- `DROP` / `TRUNCATE` 二次确认，未提交标签关闭确认。
- 应用自动管理本机密钥，使用 Argon2id + AES-256-GCM 保存密码和最近 1000 条 SQL 历史。
- 支持结果/整表 CSV 导出与表数据 INSERT SQL 导出。

## 开发环境

- macOS arm64（Apple Silicon）
- JDK 17
- Maven 3.9+
- Node.js 22+
- 与目标数据库版本相匹配的 DM JDBC JAR 或 MySQL Connector/J

MySQL Connector/J 8.3 已随应用后端内置；达梦驱动仍需按服务器版本导入。新建连接时先选择数据库类型，MySQL 默认直接使用内置驱动，也可导入其他版本：

| 数据库 | JDBC 驱动类 | 默认端口 | 连接地址 |
| --- | --- | ---: | --- |
| 达梦（DM） | `dm.jdbc.driver.DmDriver` | 5236 | `jdbc:dm://host:5236` |
| MySQL | `com.mysql.cj.jdbc.Driver` | 3306 | `jdbc:mysql://host:3306/database` |

达梦驱动可从达梦安装目录的 `drivers/jdbc` 获取，应与服务器版本匹配。MySQL 如需替换内置版本，可导入官方 Connector/J 8.x 或 9.x 的 `mysql-connector-j-*.jar`；“默认数据库”可以留空，此时先连接 MySQL 服务器，再从左侧数据库列表选择对象。

连接编辑器的“高级 JDBC 参数”每行填写一个 `key=value`。例如 MySQL 可配置 `sslMode=PREFERRED`、`connectTimeout=10000` 和 `socketTimeout=30000`；用户名、密码、数据库路径等核心参数由专用字段管理，不能通过高级参数覆盖。

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

普通语句使用分号分隔。DM 的过程、函数、触发器和匿名块使用独占一行的 `/` 结束：

```sql
CREATE OR REPLACE PROCEDURE P_TEST AS
BEGIN
  INSERT INTO T VALUES (1);
END;
/
```

MySQL 存储程序脚本支持 `DELIMITER` 切换分隔符：

```sql
DELIMITER $$
CREATE PROCEDURE p_test()
BEGIN
  INSERT INTO t VALUES (1);
END$$
DELIMITER ;
```

SQL 工作台会根据当前连接显示 `DM SQL` 或 `MySQL SQL`，并提供相应的函数、关键字和代码片段提示。
MySQL 脚本分割会读取执行前的 `@@SESSION.sql_mode`，兼容 `NO_BACKSLASH_ESCAPES` 和 `ANSI_QUOTES`；如果脚本要修改这些模式，请先单独执行 `SET SESSION sql_mode = ...`，再执行后续脚本。

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

真实达梦集成测试使用 `DM_TEST_HOST`、`DM_TEST_PORT`、`DM_TEST_USER`、`DM_TEST_PASSWORD`、`DM_TEST_SCHEMA` 和 `DM_JDBC_JAR` 注入。真实 MySQL 冒烟测试使用 `MYSQL_TEST_HOST`、`MYSQL_TEST_PORT`、`MYSQL_TEST_USER`、`MYSQL_TEST_PASSWORD`、`MYSQL_TEST_DATABASE` 和 `MYSQL_JDBC_JAR`；未完整配置时对应集成测试会自动跳过。

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

- 当前支持达梦数据库、MySQL 和中文界面。
- MySQL 没有序列和 DM“超长记录”功能，因此相关入口只在 DM 连接中显示。
- MySQL 字段若包含无符号、生成列、列级字符集/排序规则、不可见列等表设计器不能无损保留的属性，会禁止图形化修改并提示改用 SQL 工作台。
- 不包含用户权限管理、会话监控、备份恢复、表空间管理、数据导入、Excel 导出、SSH 隧道和证书向导。
- SSL 等数据库 JDBC 属性通过连接编辑器的“高级 JDBC 参数”传入。
