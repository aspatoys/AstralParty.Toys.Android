using CesiumLoader.SDK.Manifests;

// 自由相机: 滚轮缩放(沿当前视线前后移动, 视角完全不变) + 左右方向键旋转地图, F1 恢复原来的视角; 锁定镜头默认关闭, 需手动设置 autoFollow=false 启用。
[assembly: ModManifest("自由相机（双指缩放）", "2.7.2-android-pinch", "Cesium Android",
    "仅双指距离变化控制缩放；双指平移和扭转不旋转镜头。保留原相机缩放边界与自动跟随。",
    Permissions = ModPermission.ReadGameState | ModPermission.Camera | ModPermission.Input, SdkVersion = "2.8.6")]
