# 发布流程

1. 在干净的 Java 21 环境执行 `./gradlew clean build`。
2. 在测试世界完成一次“保留 Java”和一次“迁移基岩”流程，并验证备份恢复。
3. 更新 `gradle.properties` 中的 `mod_version` 与 `CHANGELOG.md`。
4. 检查仓库中没有世界、日志、密钥、数据库、服务器地址或真实玩家身份：

   ```bash
   git status --short
   git grep -nE 'key.pem|\.sqlite|\.dat_old|server\.properties'
   ```

5. 提交并创建带 `v` 的标签，例如 `v1.0.0`。
6. 推送标签。GitHub Actions 会构建 JAR、生成 SHA-256 校验文件并创建 Release。
7. 从 Release 下载产物，在全新测试服上再做一次安装验证。

正式发布前应在 GitHub 仓库设置中启用 **Private vulnerability reporting**。
