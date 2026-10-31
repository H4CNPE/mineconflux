# MineConflux

[English](README_EN.md) | 简体中文

**One identity. Both editions.**

MineConflux（MC 汇流）是一个面向 Fabric + Geyser + Floodgate 服务器的自助式 Java／基岩账号数据迁移与绑定模组。

Floodgate 原生账号链接会让基岩玩家以后使用 Java 身份登录，但不会替你处理“两个账号之前都玩过，究竟保留哪一份存档”的冲突。本项目补上了这一步：玩家先证明自己同时拥有两个角色，在网页中比较双方数据，选择保留 Java 进度或把基岩进度迁移到 Java UUID，然后写入 Floodgate 本地链接。

> **重要：** 本项目会操作真实玩家存档。请先在世界副本中测试，并在上线前做完整备份。它不是实时双向同步工具；绑定完成后，两端是使用同一个 Java UUID 和同一份存档。

## 功能

- Java 与基岩角色分别在游戏内执行 `/linkcode`，取得短期、一次性验证码。
- 网页严格区分“Java 版验证码”和“基岩版验证码”。
- 并排展示 UUID、在线状态、维度、坐标、经验、生命、饥饿、游戏时长、进度和计分板。
- 以 Minecraft 物品栏样式展示主背包、快捷栏、装备、副手和末影箱，并可查看简化 NBT。
- 可保留 Java 存档，或把基岩存档迁移到 Java UUID。
- 每次提交前备份双方 playerdata、advancements、stats 和完整 scoreboard 文件。
- 两个角色在线时拒绝迁移；提交期间阻止关联 UUID 重新进入。
- 使用 Floodgate Local Linking，使手机端以后以 Java 名称和 UUID 进入。
- 验证码失败限流、短期提交令牌、安全响应头及可选只读演示码。

## 兼容性

首个版本的测试组合：

| 组件 | 版本 |
|---|---|
| Minecraft Java Edition | 1.21.1 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.116.17+1.21.1 |
| Java | 21+ |
| Floodgate Fabric | 2.2.4 系列 |

其他 Minecraft 或 Floodgate 版本未经验证。Paper、Spigot、Velocity 单独安装均不受支持：本模组必须运行在持有世界存档的 Fabric 后端上，并能访问 Floodgate API。

## 工作原理

```text
Java 角色 ── /linkcode ──┐
                         ├─ 网页核验与存档比较 ── 备份 ── 选择数据源
基岩角色 ── /linkcode ──┘                              │
                                                       ├─ 可选：基岩存档 → Java UUID
                                                       └─ Floodgate 本地账号链接
                                                                    │
                                              Java 与基岩以后共用 Java UUID
```

它与普通“同步插件”的区别是：绑定后服务器只维护一份 Java 身份的数据，因此不存在每次上下线互相覆盖的问题。

## 安装

### 1. 准备前置组件

后端需要安装：

- Fabric Loader 与 Fabric API；
- Floodgate Fabric；
- Geyser，并把远端认证方式设为 `floodgate`；
- Floodgate Local Linking 数据库扩展（单服通常使用 SQLite）。

按照 [Floodgate 官方 Local Linking 文档](https://geysermc.org/wiki/floodgate/linking/) 下载 `floodgate-sqlite-database.jar` 或 MySQL 实现，放入 Floodgate 数据目录，并在 Floodgate 配置中启用：

```yaml
player-link:
  enabled: true
  enable-own-linking: true
  type: sqlite
```

如果使用代理，只在真正执行玩家身份链接的 Floodgate 实例上配置数据库，并确保 Fabric 后端能够取得 Floodgate 玩家信息。不要公开或提交 Floodgate 的 `key.pem`。

### 2. 安装模组

把 Release 中的 `mineconflux-<版本>.jar` 放入 Fabric 服务器的 `mods/`，然后启动服务器。首次启动会生成：

```text
config/mineconflux.json
```

默认配置：

```json
{
  "bindAddress": "127.0.0.1",
  "port": 18080,
  "codeLifetimeMinutes": 20,
  "sessionLifetimeMinutes": 30,
  "enableDemoCodes": false,
  "timeZone": "Asia/Shanghai"
}
```

修改配置后需重启 Minecraft 服务器。局域网直接访问时可把 `bindAddress` 改为服务器内网 IP 或 `0.0.0.0`；公网部署请保持回环监听，并使用 HTTPS 反向代理。详见 [部署与 HTTPS](docs/DEPLOYMENT.md)。

### 3. 验证安装

启动日志应出现：

```text
MineConflux portal listening on http://127.0.0.1:18080
```

确认网页可访问，并检查 Floodgate 日志中没有本地链接数据库加载错误。网页能打开并不代表本地链接数据库已经可写。

## 玩家使用流程

1. 用 Java 角色进入服务器，执行 `/linkcode`，保存“Java 版验证码”，然后退出服务器。
2. 用基岩角色进入同一服务器，执行 `/linkcode`，保存“基岩版验证码”，然后退出服务器。
3. 打开账号中心，把两个验证码填入各自对应的输入框。
4. 核对两个角色的身份、位置、背包、末影箱和其他进度。
5. 选择“保留 Java 进度”或“保留基岩进度并迁移绑定”。
6. 成功后重新用任意一端进入。基岩端应显示并使用 Java 角色的数据。

提交时如果任一角色仍在线，请求会被拒绝，不会主动踢人。退出后可以再次提交。

## 会迁移什么

选择保留基岩数据时：

- 整个 `playerdata/<UUID>.dat` 会复制到 Java UUID，包括背包、末影箱、位置、维度、经验、生命、饥饿、状态效果、出生点等原版玩家 NBT；
- `advancements/<UUID>.json`；
- `stats/<UUID>.json`；
- 基岩角色已有的各计分板目标分数会复制给 Java 玩家名。

选择保留 Java 数据时不会覆盖 Java 存档，只建立链接。基岩旧 UUID 的原文件不会被删除。

不会自动迁移：

- 经济、领地、权限、任务、邮件等其他插件或模组自己的数据库；
- 以旧 UUID 记录的宠物、实体所有权、白名单或封禁条目；
- 多服务器之间的独立存档；
- 非原版维度或模组把数据存放在 playerdata 之外的自定义文件。

完整边界和恢复方法见 [数据与恢复说明](docs/DATA_AND_RECOVERY.md)。

## 从源码构建

Windows PowerShell：

```powershell
.\gradlew.bat clean build
```

Linux/macOS：

```bash
./gradlew clean build
```

产物位于 `build/libs/`。普通安装使用不带 `-sources` 的 JAR。

## 安全注意事项

- 验证码和提交令牌等同于短期账号操作凭据，公网必须使用 HTTPS。
- 不要把 `world/`、日志、备份、数据库或密钥提交到仓库。
- 默认不生成演示码；如开启，演示码只允许预览假数据，不能提交绑定。
- 本项目没有跨后端分布式锁。不要让多个服务端进程同时写同一个世界目录。
- 备份中包含完整玩家数据，请像保护世界存档一样保护它。

发现漏洞请阅读 [SECURITY.md](SECURITY.md)。

## 许可证与声明

项目代码采用 [MIT License](LICENSE)。Minecraft、Geyser 和 Floodgate 的商标与资产归各自权利人所有。本项目不是 Mojang Studios、Microsoft 或 GeyserMC 的官方产品。网页运行时从 `assets.mcasset.cloud` 请求物品纹理；仓库与 Release 不打包 Minecraft 资产。
