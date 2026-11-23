# 卡牌自定义方案

入口为「自定义 → 卡牌」。可以导入已有 Win 版方案，或新建方案后为卡牌选择图片。按卡牌名称或 ID 搜索，点卡牌替换图片；单卡「恢复原图」只移除此方案对该卡的替换。

## 与 Win 版交换

使用 Win 版的「导出方案」生成 ZIP，然后在安卓版导入。安卓版导出的 ZIP 也使用同样的方案目录结构。

支持的正常方案 ZIP 结构：

```text
方案名称/
  skin.json
  UT_HandCard_10001.png
  UT_HandCard_10001_sfw.png
  UT_Event_30001.jpg
```

也可以把图片直接放在 ZIP 根目录，或使用完整的 `AstralParty_ModLoader/skins/<方案名>/` 路径。支持多个方案的完整目录包。中文文件名支持 UTF-8，以及未设置 UTF-8 标志的 GB18030/GBK ZIP。

`skin.json` 可包含 `name`、`author`、`description`。图片绑定沿用 Win 版：素材名、数字 ID 或中文卡牌名。未覆盖的卡面继续使用游戏原图，SFW 素材优先精确匹配。

动态卡面直接使用 Win 版已经转换好的文件，不在手机上转码视频：

```text
方案名称/
  UT_HandCard_10001.cardanim
  UT_HandCard_10001.frames/
    000000.png
    000001.png
```

`.cardanim` 清单为 `version=1`、`fps`、`frameCount`、`width`、`height` 五个字段；限制与 Win 版一致：1–30 fps、最多 300 帧、单边最多 560 像素、总像素最多 60,000,000。

## 应用到游戏

方案先保存到 App 内部，导入不要求访问游戏目录。点「应用」时需要 Shizuku 或 Root，以及已安装的 CesiumLoader。应用会完整写入当前加载器目录的 `skins/<方案名>/`，最后修改 `mods/CardSkinMod/config.json` 的 `Enabled` 与 `ActiveSkin`，保留其他配置。

所有写入成功后请完全重启游戏。界面的「已选择」表示已写入游戏配置，不表示当前运行中的游戏已经重新加载纹理。

若想恢复所有原卡面，点「恢复默认」。删除当前选中方案前先切换到默认；删除其他方案不会影响当前卡面。新导入的同名方案使用新的目录名，不直接覆盖已有方案。

本版只移植卡牌，不提供音效、角色或资源包编辑。
