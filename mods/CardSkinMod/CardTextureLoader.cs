using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using CesiumLoader.SDK.Logging;
using CesiumLoader.SDK.Runtime;
using FairyGUI;
using UnityEngine;

namespace CardSkinMod
{
    /// <summary>
    /// 卡面纹理加载器：将本地图片文件（PNG/JPG）加载为 FairyGUI NTexture
    /// 双通道兼容：优先使用 ImageConversion.LoadImage，回退使用 UnityWebRequestTexture
    /// </summary>
    public sealed class CardTextureLoader : IDisposable
    {
        // FairyGUI 校验 nativeTexture.name == loader.url；同一文件绑定多个资源名时必须各有纹理。
        private readonly Dictionary<string, NTexture> _textureCache = new Dictionary<string, NTexture>(StringComparer.Ordinal);
        // TextureManager may dispose an NTexture with DestroyMethod.None during a scene change.
        // Keep native ownership independently so this cannot orphan the Unity allocation.
        private readonly Dictionary<string, Texture> _nativeTextures = new Dictionary<string, Texture>(StringComparer.Ordinal);

        // 回退通道的进行中请求。绝不能每帧重建请求：上一版在 finally 里无条件 Dispose，
        // 请求还没完成就被丢弃，下一帧又从零开始，慢速/大文件会永远 pending。
        private sealed class PendingLoad
        {
            public object Request;
            public object AsyncOp;
        }

        private readonly Dictionary<string, PendingLoad> _pendingLoads =
            new Dictionary<string, PendingLoad>(StringComparer.Ordinal);

        // 回退通道成功取到纹理后，UnityWebRequest 必须活到纹理销毁之后
        // （DownloadHandlerTexture 把像素放在请求里），按 cacheKey 记账。
        private readonly Dictionary<string, IDisposable> _requestOwners =
            new Dictionary<string, IDisposable>(StringComparer.Ordinal);

        // 反射解析方法缓存
        private static bool _methodsResolved;
        private static MethodInfo _loadImageMethod;
        private static MethodInfo _getTextureMethod;
        private static bool _loadImageNonReadable;
        private static bool _requestNonReadable;
        public static bool LoadsNonReadable => _loadImageNonReadable;

        /// <summary>True when the last GetOrCreateTexture deferred because a UnityWebRequest was still pending (retryable, not a hard failure).</summary>
        public bool LastWasPending { get; private set; }

        public static void EnsureMethodsResolved()
        {
            if (_methodsResolved) return;
            _methodsResolved = true;

            try
            {
                // 通道 1: ImageConversion.LoadImage(Texture2D, byte[])
                var imgConvType = RuntimeAssemblyService.FindTypeBySimpleName("ImageConversion");
                if (imgConvType != null)
                {
                    _loadImageMethod = imgConvType.GetMethod("LoadImage", new[] { typeof(Texture2D), typeof(byte[]), typeof(bool) })
                        ?? imgConvType.GetMethod("LoadImage", new[] { typeof(Texture2D), typeof(byte[]) });
                    _loadImageNonReadable = _loadImageMethod != null && _loadImageMethod.GetParameters().Length == 3;
                }
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", "解析 ImageConversion 失败: " + ex.Message);
            }

            try
            {
                // 通道 2: UnityWebRequestTexture.GetTexture(string)
                var uwrTexType = RuntimeAssemblyService.FindTypeBySimpleName("UnityWebRequestTexture");
                if (uwrTexType != null)
                {
                    _getTextureMethod = uwrTexType.GetMethod("GetTexture", new[] { typeof(string), typeof(bool) })
                        ?? uwrTexType.GetMethod("GetTexture", new[] { typeof(string) });
                    _requestNonReadable = _getTextureMethod != null && _getTextureMethod.GetParameters().Length == 2;
                }
            }
            catch (Exception ex)
            {
                SdkLog.Warn("CardSkin", "解析 UnityWebRequestTexture 失败: " + ex.Message);
            }
        }

        /// <summary>
        /// 加载或从缓存中获取文件的 NTexture
        /// </summary>
        public NTexture GetOrCreateTexture(string filePath, string assetKey = null, int expectedWidth = 0, int expectedHeight = 0)
        {
            if (string.IsNullOrEmpty(filePath))
            {
                return null;
            }

            string cacheKey = (assetKey ?? string.Empty) + "\n" + filePath;
            if (_textureCache.TryGetValue(cacheKey, out var cached))
            {
                if (cached != null && !cached.disposed && cached.nativeTexture != null)
                {
                    return cached;
                }
                DestroyCachedTexture(cacheKey);
            }

            EnsureMethodsResolved();
            LastWasPending = false;
            NTexture loaded = LoadFromFile(cacheKey, filePath, expectedWidth, expectedHeight);
            if (loaded != null)
            {
                loaded.nativeTexture.name = assetKey ?? string.Empty;
                _textureCache[cacheKey] = loaded;
                _nativeTextures[cacheKey] = loaded.nativeTexture;
            }
            return loaded;
        }

        /// <summary>
        /// 本地文件路径转 file:// URI。
        /// 不能手拼 "file:///" + 绝对路径：Android 上绝对路径本身以 '/' 开头，
        /// 会拼出四个斜杠的 file:////data/...，UnityWebRequest 直接判为非法 URL。
        /// </summary>
        internal static string ToFileUri(string filePath)
        {
            return CardFileUri.FromPath(filePath);
        }

        /// <summary>
        /// 把已规范化为正斜杠的绝对路径拼成 file:// URI（见 CardFileUri）。
        /// 保留此转发是为了让调用方与测试都只依赖一个入口。
        /// </summary>
        internal static string ComposeFileUri(string slashedAbsolutePath)
        {
            return CardFileUri.Compose(slashedAbsolutePath);
        }

        private NTexture LoadFromFile(string cacheKey, string filePath, int expectedWidth, int expectedHeight)
        {
            try
            {
                byte[] bytes = File.ReadAllBytes(filePath);
                if (bytes == null || bytes.Length == 0) return null;
                if (expectedWidth > 0 && !CardAnimation.ValidPngHeader(bytes, expectedWidth, expectedHeight)) return null;

                // 尝试通道 1: ImageConversion
                if (_loadImageMethod != null)
                {
                    var tex = new Texture2D(2, 2, TextureFormat.RGBA32, false);
                    bool owned = false;
                    try
                    {
                        // Animated frames are never read back on the CPU; release the readable copy.
                        bool ok = (bool)_loadImageMethod.Invoke(null, _loadImageNonReadable
                            ? new object[] { tex, bytes, true } : new object[] { tex, bytes });
                        if (ok && tex != null)
                        {
                            var nTex = new NTexture(tex) { destroyMethod = DestroyMethod.None };
                            owned = true;
                            return nTex;
                        }
                    }
                    finally { if (!owned && tex != null) UnityEngine.Object.Destroy(tex); }
                }

                // 尝试通道 2: UnityWebRequestTexture 读取本地 file://
                // 请求跨帧保留在 _pendingLoads 里，完成前绝不 Dispose。
                if (_getTextureMethod != null)
                {
                    return LoadFromRequest(cacheKey, filePath);
                }

                SdkLog.Warn("CardSkin", "无法解码图片文件: " + filePath);
                return null;
            }
            catch (Exception ex)
            {
                SdkLog.Error("CardSkin", "加载卡图文件失败: " + filePath + " " + ex.Message);
                DropPending(cacheKey);
                return null;
            }
        }

        private NTexture LoadFromRequest(string cacheKey, string filePath)
        {
            PendingLoad pending;
            if (!_pendingLoads.TryGetValue(cacheKey, out pending) || pending == null || pending.Request == null)
            {
                string fileUrl = ToFileUri(filePath);
                object req = _getTextureMethod.Invoke(null, _requestNonReadable
                    ? new object[] { fileUrl, true } : new object[] { fileUrl });
                if (req == null)
                {
                    SdkLog.Warn("CardSkin", "无法解码图片文件: " + filePath);
                    return null;
                }

                object asyncOp = null;
                try
                {
                    var sendMethod = req.GetType().GetMethod("SendWebRequest", Type.EmptyTypes);
                    asyncOp = sendMethod?.Invoke(req, null);
                }
                catch (Exception ex)
                {
                    (req as IDisposable)?.Dispose();
                    SdkLog.Error("CardSkin", "发起卡图请求失败: " + filePath + " " + ex.Message);
                    return null;
                }

                pending = new PendingLoad { Request = req, AsyncOp = asyncOp };
                _pendingLoads[cacheKey] = pending;
            }

            // Non-blocking: 主线程不空转。未完成就下一帧继续看同一个请求。
            if (!IsRequestDone(pending.AsyncOp))
            {
                LastWasPending = true;
                return null;
            }

            object harvest = pending.Request;
            _pendingLoads.Remove(cacheKey);
            bool keepRequest = false;
            try
            {
                var handlerProp = harvest.GetType().GetProperty("downloadHandler");
                var handler = handlerProp?.GetValue(harvest, null);
                if (handler != null)
                {
                    var textureProp = handler.GetType().GetProperty("texture");
                    var uTex = textureProp?.GetValue(handler, null) as Texture;
                    if (uTex != null)
                    {
                        // DownloadHandlerTexture 的纹理归请求所有：请求一旦 Dispose，纹理随之失效。
                        // 因此把请求的生命周期绑到纹理上，等纹理被销毁时再释放请求。
                        try { uTex.hideFlags = HideFlags.DontSave; } catch { }
                        keepRequest = true;
                        _requestOwners[cacheKey] = harvest as IDisposable;
                        return new NTexture(uTex) { destroyMethod = DestroyMethod.None };
                    }
                }

                SdkLog.Warn("CardSkin", "卡图请求已完成但未取到纹理: " + filePath);
                return null;
            }
            finally
            {
                if (!keepRequest) (harvest as IDisposable)?.Dispose();
            }
        }

        private static bool IsRequestDone(object asyncOp)
        {
            if (asyncOp == null) return true;
            try
            {
                var isDoneProp = asyncOp.GetType().GetProperty("isDone");
                if (isDoneProp == null) return true;
                return (bool)isDoneProp.GetValue(asyncOp, null);
            }
            catch { return true; }
        }

        private void DropPending(string cacheKey)
        {
            PendingLoad pending;
            if (!_pendingLoads.TryGetValue(cacheKey, out pending)) return;
            _pendingLoads.Remove(cacheKey);
            try { (pending?.Request as IDisposable)?.Dispose(); } catch { }
        }

        public void ReleaseFile(string filePath, string assetKey)
        {
            string key = (assetKey ?? string.Empty) + "\n" + filePath;
            DropPending(key);
            DestroyCachedTexture(key);
        }

        public void RetainFiles(string first, string second)
        {
            var remove = new List<string>();
            foreach (var pair in _textureCache)
                if (!pair.Key.EndsWith("\n" + first, StringComparison.Ordinal) && !pair.Key.EndsWith("\n" + second, StringComparison.Ordinal)) remove.Add(pair.Key);
            foreach (string key in remove) DestroyCachedTexture(key);
        }

        public bool OwnsTexture(NTexture texture)
        {
            if (texture == null || texture.disposed) return false;
            foreach (var entry in _textureCache)
            {
                if (entry.Value == texture) return true;
            }
            return false;
        }

        private void DestroyCachedTexture(string key)
        {
            try
            {
                if (_textureCache.TryGetValue(key, out var texture) && texture != null && !texture.disposed)
                { texture.destroyMethod = DestroyMethod.None; texture.Dispose(); }
            }
            finally
            {
                if (_nativeTextures.TryGetValue(key, out var native) && native != null) UnityEngine.Object.Destroy(native);
                _nativeTextures.Remove(key);
                _textureCache.Remove(key);
                DropRequestOwner(key);
            }
        }

        private void DropRequestOwner(string key)
        {
            IDisposable owner;
            if (!_requestOwners.TryGetValue(key, out owner)) return;
            _requestOwners.Remove(key);
            try { owner?.Dispose(); } catch { }
        }

        public void Clear()
        {
            var keys = new List<string>(_textureCache.Keys);
            foreach (string key in keys) { try { DestroyCachedTexture(key); } catch { } }
            _textureCache.Clear();
            _nativeTextures.Clear();

            // 未完成的请求也要收尾，否则卸载后 UnityWebRequest 泄漏。
            foreach (var key in new List<string>(_pendingLoads.Keys)) { try { DropPending(key); } catch { } }
            _pendingLoads.Clear();
            foreach (var key in new List<string>(_requestOwners.Keys)) { try { DropRequestOwner(key); } catch { } }
            _requestOwners.Clear();
        }

        public void Dispose()
        {
            Clear();
        }
    }
}
