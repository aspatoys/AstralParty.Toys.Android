using System;
using System.IO;

namespace CardSkinMod
{
    /// <summary>
    /// 本地路径 → file:// URI 的唯一实现。
    ///
    /// 旧版用 "file:///" + 绝对路径 拼接：Android 上绝对路径本来以 '/' 开头，
    /// 结果得到四个斜杠的 file:////data/...，UnityWebRequest 视为非法 URL，
    /// 卡图全部回退失败。这里按平台语义归一化：
    ///   /data/...      → file:///data/...     （三个斜杠，Android/Linux）
    ///   C:/x/...       → file:///C:/x/...     （三个斜杠，Windows 盘符）
    ///   //server/share → file://server/share  （两个斜杠，UNC）
    /// 无 Unity 依赖，便于离线单测直接覆盖三个分支。
    /// </summary>
    internal static class CardFileUri
    {
        public static string FromPath(string filePath)
        {
            if (string.IsNullOrEmpty(filePath)) return string.Empty;
            return Compose(Path.GetFullPath(filePath).Replace('\\', '/'));
        }

        public static string Compose(string slashedAbsolutePath)
        {
            if (string.IsNullOrEmpty(slashedAbsolutePath)) return string.Empty;
            if (slashedAbsolutePath.StartsWith("//", StringComparison.Ordinal))
            {
                return "file:" + slashedAbsolutePath;
            }
            return "file://" + (slashedAbsolutePath.StartsWith("/", StringComparison.Ordinal)
                ? slashedAbsolutePath
                : "/" + slashedAbsolutePath);
        }
    }
}
