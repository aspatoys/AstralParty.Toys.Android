# 加载器安装与还原

安装器按完整缓存的 SHA-256 区分原版、已安装和未知版本。同一版本重复安装只更新 SDK、
引导和模组，不再重复注入游戏程序集。未知缓存不当作已安装，也不使用旧原包覆盖。
多个历史缓存存在时按修改时间选择最新的 `__data`；最新版本未适配时明确拒绝旧版本覆盖。

原版备份按版本保存在应用工作区 `backups/<originalSHA>/__data.orig`。
旧 `__data.orig` 只做读取迁移，不被重复安装覆盖。原备份损坏时保留损坏文件，
并使用 APK 内置、通过完整 SHA-256 验证的原版缓存恢复。当前内置恢复资源只对应国服游戏
3.3.0 / 资源 1.0.0，原版指纹 `77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87`。
其已安装指纹是 `37fc6d8576529a501c9a3aa0bef25ad70ad13881c0b811f9d245d434eac57991`。

游戏缓存写回先经 Shizuku / Root 子进程标准输入写入同目录临时文件，
完整校验后再 rename 替换，最后复核指纹。传输失败不截断原缓存。
游戏的 `configs/` 与模组停用状态由原有模组管理流程保留。

## 构建恢复资源

```powershell
python installer/prepare-recovery.py --bundle <经过验证的原版__data>
# 或从固定存档下载，存档与其中的 __data 都必须通过预置 SHA-256 校验：
python installer/prepare-recovery.py --download
```

资源写入已忽略的 `app/src/main/assets/cesium/recovery/original.bundle`，不把游戏二进制提交到源码库。
Gradle 同步载荷时会复核该文件，缺失或指纹不符不能构建可交付 APK。
