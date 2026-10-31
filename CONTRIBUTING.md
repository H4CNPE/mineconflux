# 参与贡献

欢迎提交 Issue 和 Pull Request。涉及玩家存档迁移的改动，请同时说明失败恢复路径，并至少验证以下场景：

1. 保留 Java 数据并建立绑定。
2. 基岩数据完整迁移到 Java UUID。
3. 任一角色在线时拒绝提交。
4. playerdata、进度或统计文件缺失时的行为。
5. 迁移中途失败后 Java 数据可由备份恢复。
6. 两个并发提交不会重复执行迁移。

构建命令：

```bash
./gradlew clean build
```

Windows PowerShell：

```powershell
.\gradlew.bat clean build
```

不要在测试夹具或日志中加入真实玩家 UUID、XUID、验证码、服务器域名、私钥和存档内容。
