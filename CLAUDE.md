# RCGallery — 项目规则

## 硬性规则

- 🚫 **永久保存 APK**：`apk_archive/` 下所有版本不允许删除
- 🚫 **包名固定**：`com.example.rcgallery` 不能改
- ✅ **APK 命名**：`RCGallery_年月日_时分.apk`
- ✅ **不覆盖**：同分钟打包只存第一份（自动检测）
- ✅ **NavHost 约束**：`AlbumGrid.route` 是 root destination，不能 `popBackStack`
- ✅ **全 overlay 架构**：AlbumGrid → MediaGrid → Preview 三层 overlay，不走 NavHost push/pop

## 开发规则

- 🔄 **先读文件分析风险**，输出重构风险和建议，**不要直接写代码**
- 📱 **新功能优先 Android 原生方案**（性能好、实现快），并先咨询用户意见
- 📦 **打包命令**：`./gradlew assembleDebug`（JDK: MS OpenJDK 17.0.19, `/c/Users/16512/.jdks/ms-17.0.19`）

## 已知问题

| 问题 | 优先级 |
|:-----|:------|
| 相册快速切换时数据闪（notifyDataSetChanged 闪烁） | 🟡 低 |
| 参数面板参数不持久化（退出 App 后重置） | 🟢 低 |
