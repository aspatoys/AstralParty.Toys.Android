# 加载器安装与还原

多个历史缓存存在时，安装器选择最近修改的 `__data`。没有现成指纹记录时，安装器在设备上
读取当前 Unity 缓存中的 `AstralParty.Runtime`，加入加载器入口并回读校验。读取或校验失败时
保留当前缓存。同一缓存重复安装只更新 SDK、引导和模组，沿用已安装的入口。

原版备份按版本保存在应用工作区 `backups/<originalSHA>/__data.orig`。
禁用或卸载时，只用与当前已安装缓存指纹匹配的原版备份。旧 `__data.orig` 只做读取迁移，
只在匹配旧版指纹时可用。APK 内置恢复资源也只对应国服游戏
3.3.0 / 资源 1.0.0，原版指纹 `77da946bf7d331351a93ff16707aab426f534b7577a771f21267478b7bc70c87`。
其已安装指纹是 `37fc6d8576529a501c9a3aa0bef25ad70ad13881c0b811f9d245d434eac57991`。

游戏缓存写回先经 Shizuku / Root 子进程标准输入写入同目录临时文件，
完整校验后再 rename 替换，最后复核指纹。传输失败不截断原缓存。
游戏的模组配置（`mods/<ModId>/config.json`）与模组停用状态由原有模组管理流程保留。

## 构建恢复资源

```powershell
python installer/prepare-recovery.py --bundle <经过验证的原版__data>
# 或从固定存档下载，存档与其中的 __data 都必须通过预置 SHA-256 校验：
python installer/prepare-recovery.py --download
```

资源写入已忽略的 `app/src/main/assets/cesium/recovery/original.bundle`，不把游戏二进制提交到源码库。
Gradle 同步载荷时会复核该文件，缺失或指纹不符不能构建可交付 APK。

## 构建本地缓存编辑器

安装 .NET 8 SDK 后运行下面的命令。构建会下载并校验固定版本的 WebAssembly 运行时。
设备上编辑时使用 Android System WebView 和 APK 内置资源，可离线运行。

```powershell
python loader/tools/CacheAssemblyPatcher/build-assets.py --output app/src/main/assets/cesium/patcher
```

## 渠道服资源

加载器页支持切换到当前用户已安装的 `com.feimo.astralparty`、`com.feimo.jxpd` 或其点分隔渠道包名。
各渠道使用各自的游戏目录、模组设置和安装备份。强制兼容默认开启，先尝试编辑当前程序集。
若当前程序集可识别但无法写入入口，再按指纹或原始大小选用 Android Direct v0.5.0 的 3.3.0 基准。
关闭强制兼容后，渠道服只能用已收录的程序集安装。遇到加密、LZMA 或无法解析的缓存时停止写回。
如果基准替换后出现登录或运行异常，可禁用加载器恢复该渠道原包。

```powershell
gh release download v0.5.0 --repo Aperfish/CesiumLoader-Android-Direct --pattern CesiumLoader-Android-ModPack-0.5.0.apk --dir channel-reference
python installer/prepare-channel-references.py --apk channel-reference/CesiumLoader-Android-ModPack-0.5.0.apk
```
