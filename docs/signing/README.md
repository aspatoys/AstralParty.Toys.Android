# 验证 Android 正式发行签名

1.2.0 沿用 1.1.2 的正式发行私钥，支持从 1.1.2 覆盖升级。这里公开的是验证证书和公钥，不包含私钥，不能用于伪造发行包。

- `production-certificate.pem`：X.509 证书，PEM 格式。
- `production-certificate.der`：同一证书，DER 格式。
- `production-public-key.pem`：RSA 公钥，SubjectPublicKeyInfo PEM 格式。
- 证书 SHA-256：`7cdb0644cd2bc4370152643950dde7b8cbf99ba4f770e094e9ecb775ca392bcc`。

使用 Android SDK Build Tools 的 `apksigner` 验证实际 APK：

```sh
apksigner verify --verbose --print-certs aspatoys-1.2.0.apk
```

命令必须成功，且输出的 `certificate SHA-256 digest` 与上面一致。新版工具可能显示 `V2 Signer`，旧版显示 `Signer #1`，以证书指纹为准。

也可核对 DER 证书文件：

```sh
sha256sum production-certificate.der
```

Release 中的 APK SHA-256 文件校验下载是否完整；证书指纹校验发行签名。旧版 1.1.0 / 1.1.1 和早期测试包使用其他签名，需先保存设置并卸载后再安装。
