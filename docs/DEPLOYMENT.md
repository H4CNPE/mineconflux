# 部署与 HTTPS

账号中心能够迁移玩家存档，验证码与会话令牌不应通过明文公网传输。推荐让模组保持默认 `127.0.0.1:18080`，只通过同机 HTTPS 反向代理访问。

## Caddy 示例

```caddyfile
link.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:18080
}
```

Caddy 能在域名正确解析、80/443 可达时自动申请证书。不要把 18080 再映射到公网。

## Nginx 示例

```nginx
server {
    listen 443 ssl http2;
    server_name link.example.com;

    ssl_certificate     /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;

    client_max_body_size 32k;

    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
    }
}
```

建议在代理或上游防火墙增加按 IP 的请求速率限制。当前版本的应用层限流读取 TCP 对端地址；经过反向代理后，它通常只能看到代理地址，因此代理层限流仍然必要。

## 局域网访问

如果只在可信局域网中测试，可以修改：

```json
{
  "bindAddress": "0.0.0.0",
  "port": 18080
}
```

重启 Minecraft 服务端后，用 `http://服务器内网地址:18080/` 访问。不要把这种配置直接暴露到互联网。

## Windows 防火墙

只有直接从其他机器访问 18080 时才需要开放入站 TCP。使用同机反向代理时无需给公网开放 18080。若必须创建局域网规则，请把 `RemoteAddress` 限制为自己的内网网段，而不是 `Any`。

## 进程生命周期

网页服务嵌入 Fabric 模组：

- Minecraft 服务端启动完成后，网页服务自动启动；
- Minecraft 服务端停止时，网页服务自动关闭；
- 修改 `config/mineconflux.json` 后需要重启 Minecraft 服务端；
- 不需要单独运行网页脚本或守护进程。
