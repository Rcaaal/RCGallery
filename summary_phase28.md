---

# 📋 第 28 阶段完整工作总结

**日期**: 2026-07-04 17:16 ~ 19:36

**APK 版本范围**: #311 → **#318**（新增 **8 个版本**） | **存档总数**: **319 个版本**

---

## 一、已查看文件

| 文件 | 说明 |

|:-----|:------|

| `AlbumGridScreen.kt` | GridVH/ListVH 星标三个层级固定 dp，星标点击去抖逻辑 |

| `MediaGridScreen.kt` | SimpleGridAdapter 双 VH，`onToggleStar` 通过 lambda 传入，update 块 Compose 布局相位 |

---

## 二、已修改文件（2 个源文件）

| 文件 | 改动类型 | 说明 |

|:-----|:---------|:-----|

| `AlbumGridScreen.kt` | 🟡 中等 | +96/-68 — 星标缩放比例 + 乐观更新 + click handler 移除中间件 + crossfade + container.requestLayout |

| `MediaGridScreen.kt` | 🔴 重度重写 | +552/-96 — 同上全部 + 灰色背景移除 + itemAnimator 恢复 |

---

## 三、APK 迭代历程（8 个版本）

### 初始状态（APK #310）

星标两个问题：① 多列下星标大小不缩放 ② 点击星标列表不刷新（需拖拽）

### 第 I 阶段：星标缩放响应式（APK #311）

**改动**: `getAlbumStarScale` / `getMediaStarScale` 函数 + GridVH `updateStarSize()` 动态 LayoutParams

**核心**: 在 `onBindViewHolder` 的 `bind()` 中根据 columns 动态缩放 3 个层级

| 层级 | 3列(1.0x) | 2列(1.15x) | 4列(0.85x) | 5列(0.7x) |

|:-----|:---------:|:----------:|:----------:|:---------:|

| 触控区 | 48dp | 55dp | 41dp | 34dp |

| 圆形背景 | 25dp | 29dp | 21dp | 18dp |

| 星标图标 | 18dp | 21dp | 15dp | 13dp |

**测试结果**: ✅ 无问题

### 第 II 阶段：星标点击不刷新 → 5 次失败尝试

| APK | 时间 | 方案 | 结果 |

|:---:|:----:|:-----|:------|

| **#312** | 18:43 | 乐观更新 `starContainer.isSelected`（改色）+ `rv.post {}` | ❌ 用户确认无效 |
| **#313** | 18:48 | click handler 内直接 `notifyDataSetChanged()` + 重排序 + Adapter 本地更新 | ❌ 5列只显示第一排/灰色缩略图闪烁 |
| **#314** | 19:06 | `notifyItemMoved` + payload，弃用 notifyDataSetChanged | ❌ 位置错乱 |
| **#315** | 19:20 | click handler notify + `iv.tag` 跳过 Coil 重加载 | ❌ 缩略图仍异常 |
| **#316** | 19:25 | `crossfade(true)` 平滑过渡 + 移除灰色背景 | ❌ 治标不治本 |

### 第 III 阶段：回退到 rv.post + container.requestLayout（APK #317-#318）

| APK | 时间 | 改动 | 结果 |

|:---:|:----:|:-----|:------|

| **#317** | 19:32 | 清除所有中间试验：click handler 只保留 `viewModel.toggleMediaStar()`；update 块 else 恢复 `rv.post {}`；移除 `itemAnimator = null`；移除 `iv.tag` 检查 | ❌ 只有第一排刷新 |
| **#318** | 19:36 | `rv.post {}` 内加 `container.requestLayout()` | ⏳ **等待测试** |

---

## 四、未解决问题

| 问题 | 优先级 | 说明 |

|:-----|:-------|:------|

| 星标点击列表仍只有第一排刷新 | 🔴 **高** | #318 添加 `container.requestLayout()` 等待用户测试结果；根因可能仍是 Compose 布局相位压制 RV 父链布局请求 |

| 参数面板参数不持久化 | 🟢 低 | 退出 App 后重置 |

| 相册快速切换数据闪烁 | 🟢 低 | `notifyDataSetChanged` 闪烁 |

| 回收站 30 天自动清空 | 🟢 低 | App 不重启则过期文件不消失 |

| 改名后 bucketId 变化星标丢失 | 🟢 低 | 已知遗留 |

---

## 五、本次经验总结

### 根因诊断

Android View 系统中，一个 View 调用 `requestLayout()` 需要**完整地从自身向上传播到 ViewRootImpl** 才能触发 measure → layout → draw 链。在 Compose `AndroidView` 架构中：

1. Compose 的 `AndroidView` 节点在布局相位中执行 `update` 块
2. 此时 Compose 正在对该节点做 layout 测量，子 View 的 `requestLayout()` **不被 Compose 传播**
3. RecyclerView 得不到完整的 measure pass → GridLayoutManager 只测得部分可见 item 的宽度 → 只有第一排刷新

`rv.post {}` 尝试绕过，但 Compose 可能在下一帧继续压制。
`container.requestLayout()` 从 RV 的父容器（FrameLayout）触发向上传播，如果 FrameLayout 本身也被 Compose 压制，同样无效。

**最终结论**: 目前没有可靠的方式在 Compose `update` 块中触发 RecyclerView 的同步布局刷新。

### 未来方向

如果本次方案仍无效，重建的架构方向：

1. **方案 A**: 在 click handler 中直接 setAdapter + setLayoutManager（完全绕过 compose 更新），代价是 RV 状态丢失（scroll 位置等）
2. **方案 B**: 用 `runOnUiThread { }` 代替 `rv.post {}`（更宽松的时序）
3. **方案 C**: 在 Compose 层用 `SideEffect` 或 `LaunchedEffect(starredMediaUris)` 延迟一帧用 `with(density)` 做强制 invalidate

---

## 六、硬性规则

### 项目约束

- 🚫 **永久保存 APK**: `apk_archive/` 不允许删除（现 **319 个版本**）
- 🚫 **包名**: `com.example.rcgallery` 不能改
- ✅ **命名格式**: `RCGallery_年月日_时分.apk`
- ✅ **不覆盖**: 同分钟打包只存第一份
- 🚫 **NavHost 约束**: `AlbumGrid.route` 是 root destination，不能 `popBackStack`
- ✅ **全 overlay 架构**: AlbumGrid→MediaGrid→Preview→TrashScreen

### 本轮确认的规则

- ✅ **预览快照数据**: PreviewScreen 通过 items 参数接收快照，不从 ViewModel 收集
- ✅ **星标不修改模型**: 星标状态独立于 data class，存在 ViewModel + SharedPreferences
- ✅ **排序不污染 ViewModel**: 排序在 composable 层 `remember` 本地处理
- ✅ **ADB 安装由我负责，功能测试由用户自己**
- ✅ **MediaGrid 显示模式持久化**: SharedPreferences key `"media_display_mode"`
- ✅ **星标缩放比例只在 `bind()` 中动态设 LayoutParams**（不在 `onCreateViewHolder` 固定 dp）
- ✅ **Compose update 块内不要调用 notifyDataSetChanged**（布局相位压制 requestLayout）— 可以用 `rv.post {}` 但通过率不稳定

### 已知不能动的

| 项目 | 说明 |

|:-----|:------|

| `apk_archive/` 历史版本 | 319 个版本 |

| `com.example.rcgallery` | 包名固定 |

| NavHost `AlbumGrid.route` | 不能 popBackStack |

| 全 overlay 架构 | 四层 overlay |

| MS OpenJDK 17.0.19 | JDK 路径固定 |

| `ic_volume_up.xml` / `ic_volume_off.xml` | SVG 路径，不要改 |

| `ic_trash.xml` / `ic_settings.xml` / `ic_restore.xml` | SVG 图标 |

| `ic_info.xml` / `ic_pip.xml` / `ic_close.xml` | SVG 图标 |

| `ic_star.xml` | SVG 图标 |

| `FpsMonitor.kt` | 保留不删 |

| Media3 `1.6.1` / Coil `2.7.0` | build.gradle.kts 固定版本 |

---

## 七、打包方式与规则

### 命令

```bash

cd D:/AndroidStudio/Projects/RCGallery

export JAVA_HOME="/c/Users/16512/.jdks/ms-17.0.19"

export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleDebug

```

### 输出

| 路径 | 说明 |

|:-----|:------|

| `app/build/outputs/apk/debug/RCGallery_*.apk` | 最新编译 |

| `apk_archive/RCGallery_*.apk` | **永久存档**（319 个版本） |

### 最终版本

| 项目 | 值 |

|:----|:-----|

| **最新 APK** | `RCGallery_20260704_1936.apk`（#318） |

| **本轮净增** | +648/-164 行，8 个 APK 版本，0 个 commit（未提交） |

| **编译状态** | ✅ BUILD SUCCESSFUL |

| **Git** | `95a8751` `master`（本轮修改尚未 commit） |

| **模拟器 ADB** | `D:\AndroidSDK\platform-tools\adb.exe` |

---
