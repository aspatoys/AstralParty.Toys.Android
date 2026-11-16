using System;
using System.Collections.Generic;
using System.IO;
using CesiumLoader.SDK;
using CesiumLoader.SDK.Gameplay;
using CesiumLoader.SDK.Logging;
using CesiumLoader.SDK.Manifests;
using CesiumLoader.SDK.Mods;
using GameLogic;
using party.model;

[assembly: ModManifest("手牌卡面皮肤", CardSkinMod.ModEntry.ModVersion, "CesiumLoader",
    "自定义手牌、事件、命运、地图事件及图鉴卡面，支持素材名、ID和中文名，支持序列帧动态卡面与显式动态异画，静态异画及未指定卡面保留原版",
    Permissions = ModPermission.ReadGameState | ModPermission.UI, SdkVersion = "2.8.6")]

namespace CardSkinMod
{
    /// <summary>
    /// 卡面皮肤模组入口。
    /// 配置语义（与桌面版 AstralParty.Toys 一致）：
    ///   Enabled=false                 → 游戏默认卡面，不做任何替换（默认状态）
    ///   Enabled=true + ActiveSkin=xxx → 使用加载器目录 skins/xxx 中的卡面
    /// 配置变更由引导线程轮询后在主线程回调 OnConfigChanged，本类在此安全卸载并重建，
    /// 已注入的贴图会恢复到游戏原始卡面。
    /// </summary>
    public sealed class ModEntry : ModBase
    {
        public const string ModVersion = "1.2.2";

        /// <summary>与桌面版一致的“游戏默认卡面”哨兵值。</summary>
        public const string GameDefaultSkin = "__game_default__";

        public override string Name => "手牌卡面皮肤";
        public override string Version => ModVersion;

        private CardSkinModel _model;
        private CardTextureLoader _loader;
        private CardSkinApplier _applier;

        private bool _enabled;
        private string _activeSkin = GameDefaultSkin;
        private bool _forceFullCard;
        private int _animationCacheMiB = 512;

        private bool _subscribed;
        private long _nextCachePoll;

        public static void Main()
        {
            SdkManifest.ExportSidecar();
            Run(new ModEntry(), tag: "CardSkin");
        }

        public override void OnLoad()
        {
            // 值只在 OnInitialize 读取；OnLoad 阶段 Config 可能尚未完成首轮加载。
        }

        public override void OnInitialize()
        {
            try
            {
                ReadConfig(writeBack: true);
                // 订阅与钩子常驻：即便当前为禁用状态，也需要在用户于自定义页启用后立即生效。
                Subscribe();
                if (!_enabled)
                {
                    SdkLog.Info("CardSkin", "模组当前为禁用状态（Enabled=false），保持游戏默认卡面。");
                    return;
                }
                Activate();
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", "初始化失败，已回退游戏默认卡面: " + ex.Message);
                Deactivate();
            }
        }

        /// <summary>
        /// 配置热切换：引导线程轮询到 configs/CardSkinMod.json 变化后在主线程调用。
        /// 先完整卸载（恢复原始卡面、释放贴图），再按新配置重建。
        /// </summary>
        public override void OnConfigChanged()
        {
            try
            {
                ReadConfig(writeBack: false);
                Deactivate();
                if (!_enabled)
                {
                    SdkLog.Info("CardSkin", "配置已更新为禁用，卡面已恢复游戏默认。");
                    return;
                }
                Activate();
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", "应用新配置失败，已回退游戏默认卡面: " + ex.Message);
                Deactivate();
            }
        }

        private void ReadConfig(bool writeBack)
        {
            // 首次安装默认禁用：未在自定义页明确启用前不改动任何卡面。
            _enabled = Config?.GetBool("Enabled", false) ?? false;
            _activeSkin = Config?.GetString("ActiveSkin", GameDefaultSkin) ?? GameDefaultSkin;
            if (string.IsNullOrEmpty(_activeSkin)) _activeSkin = GameDefaultSkin;
            _forceFullCard = Config?.GetBool("ForceFullCard", false) ?? false;
            _animationCacheMiB = Config?.GetInt("AnimationCacheMiB", 512) ?? 512;

            CardSkinConfigDocs.Apply(Config);
            if (!writeBack) return;
            // 首次运行时补齐键，方便用户在自定义页/文本编辑器里直接修改。
            Config?.SetDefault("Enabled", _enabled);
            Config?.SetDefault("ActiveSkin", _activeSkin);
            Config?.SetDefault("ForceFullCard", _forceFullCard);
            Config?.SaveIfDirty();
        }

        private void Subscribe()
        {
            if (_subscribed) return;
            _subscribed = true;
            GameEvents.HandChanged += OnHandChanged;
            GameEvents.StartAutoHook();
        }

        /// <summary>按当前配置建立模型与注入器，并加载皮肤目录。</summary>
        private void Activate()
        {
            string skinName = NormalizeSkinName(_activeSkin);
            if (skinName == GameDefaultSkin)
            {
                // 与桌面版一致：__game_default__ 表示明确使用官方卡面，不注入任何贴图。
                SdkLog.Info("CardSkin", "当前方案为游戏默认卡面（__game_default__），不做任何替换。");
                return;
            }

            _model = new CardSkinModel();
            _loader = new CardTextureLoader();
            _applier = new CardSkinApplier(_model, _loader, _forceFullCard, _animationCacheMiB);

            string skinDirectory;
            try
            {
                skinDirectory = ResolveSkinDirectory(skinName);
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", ex.Message + " 将保持游戏默认卡面。");
                return;
            }

            bool loaded = _model.LoadSkinDirectory(skinDirectory, skinName);
            if (loaded && _model.FileCount > 0)
            {
                SdkLog.Info("CardSkin", $"已加载皮肤方案 [{skinName}]（{skinDirectory}），包含 {_model.FileCount} 张自定义卡图、{_model.AnimationCount} 组动态卡面。");
            }
            else
            {
                SdkLog.Warn("CardSkin", $"皮肤方案 [{skinName}] 目录不存在或为空（{skinDirectory}），将全量回退官方卡面。");
            }

            SdkLog.Info("CardSkin", "卡面皮肤已就绪：手牌、事件、命运、地图事件与图鉴，未覆盖的卡面保留原版。");
        }

        /// <summary>卸载注入并恢复原始卡面。</summary>
        private void Deactivate()
        {
            try { _applier?.RemoveInjectedTextures(); }
            catch (Exception ex) { SdkLog.Warn("CardSkin", "恢复原始卡面失败: " + ex.Message); }
            try { _loader?.Dispose(); }
            catch { }
            _applier = null;
            _loader = null;
            _model = null;
        }

        private void OnHandChanged(long playerId, IReadOnlyList<CardInfo> cards)
        {
            if (!_enabled || _applier == null) return;
            try
            {
                // 让下一次引擎更新立即重扫，避免加载回调重入动画枚举。
                _applier.ApplyToHandCardPanel();
            }
            catch { }
        }

        public override void OnUpdate()
        {
            if (!_enabled || _applier == null) return;

            long now = DateTime.UtcNow.Ticks / 10000;

            try
            {
                // 大厅也维护缓存；场景切换清空 TextureManager 后会在下一轮恢复。
                if (now >= _nextCachePoll)
                {
                    _nextCachePoll = now + 1000;
                    _applier.InjectTextureManager();
                }
                _applier.ApplyToVisibleCards();
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", "Update 轮询异常: " + ex.Message);
            }
        }

        public override void OnUnload()
        {
            try
            {
                if (_subscribed)
                {
                    GameEvents.HandChanged -= OnHandChanged;
                    _subscribed = false;
                }
                Deactivate();
                SdkLog.Info("CardSkin", "模组已卸载并释放全部缓存贴图。");
            }
            catch { }
        }

        /// <summary>
        /// 皮肤方案名归一化：桌面版的 __game_default__ 表示不替换任何卡面。
        /// </summary>
        private static string NormalizeSkinName(string skinName)
        {
            if (string.IsNullOrEmpty(skinName)) return GameDefaultSkin;
            return skinName.Trim();
        }

        /// <summary>
        /// 解析皮肤目录：统一走 SDK 的 SdkPath（CESIUM_LOADER_DIR / CESIUM_MODS_DIR 推导），
        /// 保证与加载器其它组件看到的 CesiumLoader/skins/&lt;profile&gt; 完全一致。
        /// HybridCLR 下不可使用 AppDomain.BaseDirectory。
        /// </summary>
        private static string ResolveSkinDirectory(string skinName)
        {
            if (string.IsNullOrEmpty(skinName)) skinName = "default";

            string directory = null;
            try
            {
                directory = SdkPath.GetSkinDirectory(skinName);
            }
            catch
            {
                directory = null;
            }

            if (!string.IsNullOrEmpty(directory) && Directory.Exists(directory))
            {
                return directory;
            }

            // 兜底：CESIUM_MODS_DIR 的父目录 + skins/<name>。
            string modsDir = Environment.GetEnvironmentVariable("CESIUM_MODS_DIR");
            if (!string.IsNullOrEmpty(modsDir))
            {
                string loaderRoot = Path.GetDirectoryName(modsDir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                if (!string.IsNullOrEmpty(loaderRoot))
                {
                    return Path.Combine(loaderRoot, "skins", skinName);
                }
            }

            if (!string.IsNullOrEmpty(directory)) return directory;

            throw new InvalidOperationException("无法定位皮肤目录：SdkPath 与 CESIUM_MODS_DIR 均不可用。");
        }
    }
}
