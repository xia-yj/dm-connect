# DM Connect 2.0.11

DM Connect 是支持 DM、MySQL、MongoDB、Redis、PostgreSQL、Oracle、SQL Server 与 SQLite 的 macOS 数据库桌面工具。2.0 版使用 **React + Electron + Java 17 后端**：界面使用 React，JDBC、原生客户端、对象元数据、SQL 执行、事务、本地加密存储和数据导出核心由 Java 负责。

## 架构

```text
React 渲染进程（无 Node.js / 无文件系统权限）
        ↕ 受限 contextBridge API
Electron 主进程（校验页面来源与方法白名单）
        ↕ stdin/stdout 单行 JSON RPC（不开放网络端口）
Java 17 后端（JDBC / 元数据 / SQL / 事务 / 本地加密存储 / CSV）
        ↕ 独立 URLClassLoader
应用内置 MySQL、PostgreSQL、SQL Server、SQLite JDBC 驱动及 MongoDB/Redis 客户端；DM、Oracle 等驱动由用户导入
```

支持功能：

- 新建、编辑、复制、删除、测试和断开八种数据库连接，并按连接类型隔离 JDBC 驱动或原生客户端。
- 关系型数据库连接卡片支持独立展开/收起库列表，多个连接可以同时展开；MongoDB 与 Redis 的库和对象在原生工作台中浏览。
- 懒加载关系型数据库的模式、表、视图、过程、函数、序列和触发器（按数据库实际能力显示结果）。
- 双击表名默认展示最多 100 行数据预览，同时可查看列、约束、索引和 DDL。
- 按数据库类型创建和修改表，提供 DM / MySQL 对应的字段类型、DDL 与安全校验。
- Monaco SQL 多标签工作台，每个标签拥有独立 JDBC 会话、事务状态和方言提示。
- 执行选中内容、当前语句或整个脚本，可取消执行，支持多结果集。
- `DROP` / `TRUNCATE` 二次确认，未提交标签关闭确认。
- SQL 标签页支持右键保存当前脚本到本地文件夹、双击或右键重命名，保存时使用自定义标签名生成 `.sql` 文件。
- 应用自动管理本机密钥，使用 Argon2id + AES-256-GCM 保存密码和最近 1000 条 SQL 历史。
- 支持结果/整表 CSV 导出与表数据 INSERT SQL 导出。

## 开发环境

- macOS arm64（Apple Silicon）
- JDK 17
- Maven 3.9+
- Node.js 22+
- 与目标数据库版本相匹配的 DM 或 Oracle JDBC JAR；其他内置驱动也可导入替代版本

MySQL Connector/J 8.3 已随应用后端内置；达梦驱动仍需按服务器版本导入。新建连接时先选择数据库类型，MySQL 默认直接使用内置驱动，也可导入其他版本：

| 数据库 | JDBC 驱动类 | 默认端口 | 连接地址 |
| --- | --- | ---: | --- |
| 达梦（DM） | `dm.jdbc.driver.DmDriver` | 5236 | `jdbc:dm://host:5236` |
| MySQL | `com.mysql.cj.jdbc.Driver` | 3306 | `jdbc:mysql://host:3306/database` |
| MongoDB | 内置原生客户端 | 27017 | `mongodb://host:27017/database` |
| Redis | 内置原生客户端 | 6379 | `redis://host:6379` |
| PostgreSQL | `org.postgresql.Driver`（内置） | 5432 | `jdbc:postgresql://host:5432/database` |
| Oracle | `oracle.jdbc.OracleDriver`（导入） | 1521 | `jdbc:oracle:thin:@//host:1521/service` |
| SQL Server | `com.microsoft.sqlserver.jdbc.SQLServerDriver`（内置） | 1433 | `jdbc:sqlserver://host:1433;databaseName=database` |
| SQLite | `org.sqlite.JDBC`（内置） | — | `jdbc:sqlite:/path/to/database.db` |

达梦驱动可从达梦安装目录的 `drivers/jdbc` 获取，应与服务器版本匹配。MySQL 如需替换内置版本，可导入官方 Connector/J 8.x 或 9.x 的 `mysql-connector-j-*.jar`；“默认数据库”可以留空，此时先连接 MySQL 服务器，再从左侧数据库列表选择对象。

连接编辑器的高级参数每行填写一个 `key=value`。例如 MySQL 可配置 `sslMode=PREFERRED`，PostgreSQL 可配置 `sslmode=prefer`，SQL Server 可配置 `encrypt=true`。通用 JDBC 驱动的高级参数通过 `Driver.connect` 的 `Properties` 传递，不拼接到 URL；用户名、密码、Token、Secret 等凭据只能使用专用字段，不能明文写入高级参数。

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

SQL 工作台会根据当前 JDBC 连接显示对应方言名称；DM/MySQL 额外提供函数、关键字和代码片段提示。
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

应用默认从 GitHub Releases 读取更新清单：

```text
https://github.com/xia-yj/dm-connect/releases/latest/download/update.json
```

也可以在应用“设置 → 应用更新”中改成其他地址。GitHub Actions 在推送 `v*` tag 时会自动同步桌面端版本、构建 Windows/macOS 安装包，并生成更新清单和 GitHub Release。应用下载包含完整 `.app` 的 ZIP，退出后由临时脚本替换旧应用并重新启动，因此首次仍应手动安装 DMG。

## 当前边界

- MongoDB 提供数据库/集合浏览、Extended JSON 文档查询与单文档新增、替换和删除，支持 `authSource`、SCRAM、TLS、单个 seed、可选 `replicaSet` 与连接超时；暂不支持 SRV、多 seed、X.509 或每连接独立 CA/mTLS。
- Redis 当前面向 standalone 实例，支持 ACL/密码、TLS、逻辑库、游标分页 SCAN 和键值读取；暂不支持 Sentinel/Cluster 或每连接独立 CA/mTLS。会破坏会话、无限阻塞或可能返回无界大结果的命令会被命令台拦截，其余写入/管理命令要求二次确认。
- PostgreSQL、Oracle、SQL Server、SQLite 首版支持对象浏览、筛选/分页预览、主外键与索引查看和 SQL 工作台。SQLite 从 `sqlite_schema`、Oracle 从 `DBMS_METADATA` 读取权威 DDL；PostgreSQL/SQL Server 的视图或程序定义是对象定义片段，不等同于完整备份，表 DDL会提示使用官方导出工具。
- Oracle 当前只提供 TCP Thin 的 service-name、用户名/密码基础连接；SID、TNS alias、Package/Package Body、Cloud Wallet/TCPS 伴随 JAR 组尚未支持。Oracle 分页使用 12c 起提供的 `OFFSET/FETCH`。
- 图形化表设计器暂仅适用于 DM/MySQL。
- MySQL 没有序列和 DM“超长记录”功能，因此相关入口只在 DM 连接中显示。
- MySQL 字段若包含无符号、生成列、列级字符集/排序规则、不可见列等表设计器不能无损保留的属性，会禁止图形化修改并提示改用 SQL 工作台。
- 不包含用户权限管理、会话监控、备份恢复、表空间管理、数据导入、Excel 导出、SSH 隧道和证书向导。
- SSL 等数据库 JDBC 属性通过连接编辑器的“高级 JDBC 参数”传入。
