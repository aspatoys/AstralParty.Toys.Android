# 软件与加载器更新

## 加载器页的安装、更新与管理

安装 Android 加载器并连接 Shizuku / Root 后，在「加载器」的 CesiumLoader 卡片点「检查 GitHub 最新版」。确认版本后点更新，先完全退出游戏，再确认开始更新。完成后重启游戏。

地址与 Win 版保持一致：

- 仓库：https://github.com/higashitaniyume/CesiumLoader
- 静态版本：https://github.com/higashitaniyume/CesiumLoader/releases/latest/download/version.json
- API 兜底：https://api.github.com/repos/higashitaniyume/CesiumLoader/releases/latest
- 固定下载地址：https://github.com/higashitaniyume/CesiumLoader/releases/latest/download/cesium-loader.zip
- 实际下载锁定检查到的版本化资产，避免检查与下载之间 latest 切换。

更新先校验整个 ZIP，再校验其中每个准备部署文件的 SHA-256。只导入 SDK 与普通模组及其资源，保留 Android bootstrap、注入程序集、恢复包、悬浮窗、安卓变速和自由相机。Win 包中的这三个同名模组不部署；LanguageUnlockMod 延续既有 Android 排除策略。

集中 `configs/`、皮肤和其他用户数据不删除；包内 config.json / mod.cfg 不覆盖。停用模组更新后仍放在 disabled_mods。旧平铺布局的同名 DLL 退出扫描，避免加载两份。安装版本记录最后提交。

同一模组同时存在于 mods 与 disabled_mods 时，更新器保留该模组全部本地文件和目录状态，跳过其远程 DLL、元数据、资源及旧平铺文件清理，继续更新 SDK 和其他模组。完成后显示保留的模组名称；不会代替用户选择启用或停用。

网络、校验或写入错误会显示原因，写入异常会回滚已经提交的文件；若回滚本身失败，保留备份路径供恢复。强杀进程、掉电或存储故障无法由进程内回滚保证恢复，因此请退出游戏后再更新，更新时不要强杀工具箱。

## 安装、禁用与卸载

加载器页把版本、GitHub 更新、安装及管理操作集中在同一卡片，内置悬浮窗卡片紧接其下。

- 「安装内置加载器」部署 APK 内置版本，适合首次安装或修复；会覆盖 SDK 和随包模组，因此在线更新后不应把它当作启用按钮。
- 「禁用加载器」恢复经过指纹校验的原包入口，保留整个加载器目录、模组、配置、皮肤和预设。下次启动游戏生效。
- 「启用加载器」重新安装已验证的游戏入口，使用本地现有 SDK 和模组，不重新部署内置旧版。
- 「卸载加载器」替代原来的「还原原包」。先恢复并再次校验游戏原包，成功后删除实际 Android 加载器目录 `/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader`，包括所有模组、配置、皮肤、预设和日志；同时删除工具内的安装备份、传输缓存及已下载的加载器包和临时解包目录。APK 自带的只读资源随应用保留。

卸载前弹窗说明数据会永久删除。原包恢复失败时不删除加载器文件；加载器目录删除失败时保留安装备份供重试。所有操作前应完全退出游戏，结束后重新启动。

## 软件更新

「设置 → 软件更新」提供检查、下载、安装三个操作，以及自动检查、自动下载、自动打开系统安装开关。自动检查在打开应用时触发，不是后台定时任务；下载在当前应用进程继续进行。进程退出后可以重新检查和下载。

软件更新使用 Android 仓库：`higashitaniyume/AstralParty.Toys.Android`，不会下载 Win EXE。优先 version.json 的 androidApk 包，旧发行版没有清单时读取 GitHub Release API。未发现新版本不下载，不请求安装权限。

下载后验证 APK 的包名、版本号递增、SHA-256，以及与当前应用和固定正式证书一致的签名。签名 SHA-256 为 `7cdb0644cd2bc4370152643950dde7b8cbf99ba4f770e094e9ecb775ca392bcc`。验证失败不会打开安装界面。

安装需要 Android 系统确认，首次可能需要开启此应用的「允许安装未知应用」。自动安装只是在应用前台打开系统安装器，不能绕过系统确认。取消后可手动点安装重试。安装界面采用受限 FileProvider 共享 APK，不开放整个缓存目录。

## 镜像与发布

和 Win 版共用四种镜像选择：GitHub 直连、gh-proxy.com（默认）、ghfast.top、自定义 HTTPS 前缀。设置供两个更新流程共用；镜像错误可以切换直连重试。下载仍会校验文件哈希，APK 还会校验证书。

发布时把 `tools/write-update-manifest.py` 生成的 version.json 与签名后的 APK 一起上传，清单必须记录最终签名产物的哈希。CI 已接入生成与上传流程。手工发布也应上传该文件；缺失时客户端会尝试 Release API 兜底。
