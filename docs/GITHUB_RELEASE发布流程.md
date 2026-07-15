# GitHub 构建与自动更新流程

本文档说明 DM Connect 从提交代码到用户自动更新的完整流程。

## 一、版本号规则

应用版本号位于：

```text
desktop/package.json
desktop/package-lock.json
```

两个文件的版本号必须一致。Tag 使用 `v` 加版本号，例如：

```text
应用版本：2.0.8
Git Tag：v2.0.8
```

## 二、提交代码并发布版本

先完成代码修改并提交：

```bash
git add .
git commit -m "feat: 描述本次修改"
git push origin main
```

修改版本号后，创建并推送 Tag：

```bash
git tag v2.0.9
git push origin v2.0.9
```

Tag 推送后会自动触发 GitHub Actions。不要重复使用已经发布过的 Tag；如果需要重新发布，应增加补丁版本号，例如 `v2.0.10`。

## 三、GitHub Actions 构建内容

工作流文件：

```text
.github/workflows/build-windows.yml
```

每个版本会构建以下产物：

| 平台 | 架构 | 产物 |
| --- | --- | --- |
| Windows | x64 | `.exe` 安装包 |
| macOS | arm64 | `.dmg`、`.app.zip` |
| macOS | x64 Intel | `.dmg`、`.app.zip` |

Windows 构建使用 `scripts/package-windows.ps1`，macOS 构建使用 `scripts/package-macos.sh`。

也可以在 GitHub 的 **Actions → Build desktop installers → Run workflow** 手动构建。手动构建只产生 Artifacts，不会创建 Release；只有推送 `v*` Tag 才会自动发布 Release。

## 四、Release 自动发布

Tag 构建成功后，工作流会自动：

1. 下载 Windows 和两个 macOS 构建产物。
2. 生成 `update.json`。
3. 创建对应版本的 GitHub Release。
4. 将 EXE、DMG、更新 ZIP 和 `update.json` 上传到 Release。

Release 地址：

```text
https://github.com/xia-yj/dm-connect/releases
```

## 五、客户端自动更新

客户端默认更新清单地址为：

```text
https://github.com/xia-yj/dm-connect/releases/latest/download/update.json
```

`update.json` 会根据客户端架构选择对应的更新包：

```json
{
  "version": "2.0.9",
  "build": "v2.0.9",
  "arm64Url": "https://github.com/xia-yj/dm-connect/releases/download/v2.0.9/DM-Connect-2.0.9-arm64.app.zip",
  "x64Url": "https://github.com/xia-yj/dm-connect/releases/download/v2.0.9/DM-Connect-2.0.9-x64.app.zip",
  "notes": "DM Connect v2.0.9"
}
```

客户端点击“检查更新”后，会读取清单、比较版本号，并下载对应架构的更新 ZIP。macOS 支持下载完成后自动替换应用并重启。

## 六、常见检查项

- GitHub 仓库必须保持公开，否则客户端无法直接读取 Release 文件。
- `desktop/package.json`、`desktop/package-lock.json` 和 Tag 版本号必须一致。
- Tag 必须以 `v` 开头，例如 `v2.0.9`。
- Actions 失败时先打开失败的 job，查看 `Build x64 installer` 或 `Build macOS ... packages` 日志。
- Release 只有在所有构建 job 成功后才会创建。
- 手动运行 Actions 不会创建 Release。
- Windows 当前已构建 EXE；macOS 当前支持自动替换更新，Windows 自动替换安装逻辑需单独实现。

## 七、完整示例

```bash
# 修改 desktop/package.json 和 desktop/package-lock.json 为 2.0.9
git add desktop/package.json desktop/package-lock.json
git commit -m "chore: bump application version to 2.0.9"
git push origin main

# 触发构建和 Release
git tag v2.0.9
git push origin v2.0.9
```

完成后，在 GitHub Releases 页面即可看到 `DM Connect v2.0.9` 及所有平台安装包。
