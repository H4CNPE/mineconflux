# 数据范围、备份与恢复

## 两种选择

### 保留 Java 进度

模组不会覆盖 Java UUID 的存档，只向 Floodgate 本地链接数据库写入“该基岩身份以后使用此 Java UUID”。基岩旧 UUID 的存档继续留在世界目录，但正常绑定登录时不会再被使用。

### 保留基岩进度

模组先备份双方数据，然后把基岩 UUID 对应的 playerdata、进度与统计复制到 Java UUID，并把相关计分板分数复制到 Java 玩家名，最后建立 Floodgate 本地链接。源文件不会被删除。

## 备份目录

每次真实提交都会生成：

```text
mineconflux/backups/<时间>-<Java名>-<基岩名>/
├── metadata.json
├── java-playerdata.dat
├── java-advancements.json
├── java-stats.json
├── bedrock-playerdata.dat
├── bedrock-advancements.json
├── bedrock-stats.json
└── scoreboard.dat
```

不存在的可选文件不会出现在目录中。`metadata.json` 记录双方名称与 UUID。备份目录位于世界目录的同级服务端根目录下。

此备份不包含 Floodgate 本地链接数据库，也不是完整世界备份。生产操作前仍应备份整个世界和 Floodgate 数据目录。

## 手动回滚

1. 完全停止 Minecraft 服务端，确认没有 Java 进程持有世界文件。
2. 备份当前世界与 Floodgate 数据目录，保留故障现场。
3. 从 `metadata.json` 取得 `javaUuid`。
4. 将 `java-playerdata.dat` 复制回 `world/playerdata/<javaUuid>.dat`。
5. 将 Java 的 advancements 与 stats 文件复制回对应目录；若备份中原本不存在该文件，则删除迁移后新生成的同 UUID 文件。
6. 如需恢复计分板，把 `scoreboard.dat` 复制回 `world/data/scoreboard.dat`。这会回退备份之后所有玩家的计分板变更，因此应谨慎处理。
7. 通过 Floodgate 支持的解除链接流程移除本地链接；必要时恢复操作前备份的 Floodgate 数据目录。
8. 启动服务器并分别验证 Java 与基岩身份。

不要在服务器运行时直接覆盖 `.dat` 文件。

## 不在范围内的数据

整个原版 playerdata NBT 会随文件复制，但以下数据通常由其他系统按 UUID 或玩家名独立保存，本项目不会自动重写：

- 权限组、经济余额、领地、任务、邮件、拍卖与聊天插件数据；
- 宠物、驯服实体、展示实体或其他带 Owner UUID 的世界实体；
- 白名单、封禁列表和第三方认证数据；
- 模组在自己的配置、数据库或世界附件中保存的数据；
- 代理层数据库与其他后端服务器的玩家数据。

上线前应列出服务器所有会按 UUID 保存数据的插件或模组，并决定是否需要额外迁移适配器。
