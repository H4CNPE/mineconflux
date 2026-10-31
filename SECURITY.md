# 安全策略

## 支持范围

安全修复只保证应用于最新发布版本。报告问题时请说明 Minecraft、Fabric Loader、Fabric API、Floodgate 与本模组版本。

## 报告漏洞

请不要在公开 Issue 中提交可利用细节、真实验证码、玩家 UUID、XUID、存档、Floodgate 数据库或服务器地址。仓库建立后，请使用 GitHub 的 **Security → Report a vulnerability** 私密报告功能。

## 部署要求

- 默认只监听 `127.0.0.1:18080`。不要直接把 HTTP 端口映射到公网。
- 对外服务必须使用 HTTPS 反向代理，并限制请求体、连接速率和来源。
- 不要提交 `world/`、`mineconflux/backups/`、Floodgate 数据库、`key.pem` 或日志。
- 绑定会改变玩家身份映射并可能覆盖 Java 存档。首次使用前必须进行完整世界备份，并先在副本上测试。
- 管理员应定期清理旧备份；备份包含完整玩家物品和身份信息。

## 已知边界

此项目依赖 Floodgate 的本地账号链接功能。它不提供跨服务器分布式锁，也不应让多个后端同时写入同一世界目录。内置网页服务器不终止 TLS，公网部署必须在其前方放置反向代理。
