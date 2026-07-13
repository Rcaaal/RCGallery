package com.example.rcgallery.ui.component

/**
 * 全局惯性物理参数（运行时修改，不需重启）。
 */
object InertiaSettings {
    /** X轴速度乘数：惯性速度放大倍数。越大滑越远 */
    @JvmStatic var speedMultiplierX: Float = 10.0f

    /** Y轴速度乘数：惯性速度放大倍数。越大滑越远 */
    @JvmStatic var speedMultiplierY: Float = 12.0f

    /** 衰减系数分母（内部参数，不显示在面板）：越小滑越远 */
    @JvmStatic var decay: Float = 0.07f

    /** X轴动画时长倍率（ms/px）：距离 × 此值 = 动画时长。越小移动越快 */
    @JvmStatic var durationMultiplierX: Float = 0.2f

    /** Y轴动画时长倍率（ms/px）：距离 × 此值 = 动画时长。越小移动越快 */
    @JvmStatic var durationMultiplierY: Float = 0.2f

    /** 翻页触发阈值（px）：放大后单帧平移 ≥ 此值才触发翻页。越大越不容易误触 */
    @JvmStatic var edgeSwipeMinPx: Float = 24f

    /** 长按倍速：长按视频播放的倍率（2x-10x） */
    @JvmStatic var longPressSpeed: Float = 2f

    /** 长按倍速触发阈值（秒）：手指按下 ≥ 此值才进入倍速。默认 1.0 秒 */
    @JvmStatic var longPressTimeoutSec: Float = 1.0f

    /** 控制区调试框：在视频播放页显示进度条/时间区的红色虚线框 */
    @JvmStatic var showControlZoneDebug: Boolean = false

    /** 控制栏按钮保护区域（占屏幕高度百分比，从底部算起）：双击不触发范围，默认 25% */
    @JvmStatic var controlBarZonePercent: Float = 25f

    /** 上划/下滑速度阈值（px）：值越小越容易触发。默认 5，范围 1~15 */
    @JvmStatic var swipeVelocityThreshold: Float = 5f

    /** 静音按钮底部偏移（dp）：值越大按钮越往上抬。默认 52，范围 0~160 */
    @JvmStatic var muteButtonBottomDp: Float = 52f
}
