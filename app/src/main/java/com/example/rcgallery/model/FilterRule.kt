package com.example.rcgallery.model

/**
 * 筛选规则系统 — 数据模型 + 冲突检测。
 *
 * 规则叠加方式：多条已启用的规则 OR 叠加（满足任一规则即显示/隐藏）。
 * 冲突定义：两条规则对同一标签的处理口径不一致时视为冲突，不能同时启用。
 */

/** 规则逻辑 */
enum class FilterLogic { AND, OR }

/** 规则模式 */
enum class FilterMode { HIDE, SHOW_ONLY }

/** 规则作用域 */
enum class FilterScope { ALBUM, MEDIA, BOTH }

/** 父级相册在本地相册页中的显示范围。 */
enum class ParentDisplayFilter {
    ALL,
    ONLY_PARENTS,
    HIDE_PARENTS
}

/**
 * 一条持久筛选规则。
 *
 * 语义：如果某条目的标签集合满足（logic 组合）此规则的 tagNames，
 * 则按 mode 处理（隐藏/仅显示），该处理作用于 scope 指定范围。
 *
 * 多条已启用规则 OR 叠加：条目满足任一规则的条件即被处理。
 *
 * @param id 唯一标识（UUID 字符串）
 * @param name 用户定义名称
 * @param tagNames 目标标签名称集合
 * @param logic AND=必须全部命中，OR=命中任一
 * @param mode HIDE=隐藏符合条件的对象，SHOW_ONLY=仅显示符合条件的对象
 * @param scope 规则作用范围
 * @param enabled 是否已启用
 */
data class TagRule(
    val id: String = "",
    val name: String = "",
    val tagNames: List<String> = emptyList(),
    val logic: FilterLogic = FilterLogic.OR,
    val mode: FilterMode = FilterMode.HIDE,
    val scope: FilterScope = FilterScope.ALBUM,
    val enabled: Boolean = false
) {
    /** 规则简短的描述文本（供 UI 列表展示） */
    val summary: String
        get() {
            val modeLabel = if (mode == FilterMode.HIDE) "隐藏" else "仅显示"
            val tagLabel = tagNames.joinToString(if (logic == FilterLogic.AND) " + " else " / ")
            return "$modeLabel: $tagLabel"
        }
}

/**
 * 临时筛选配置（仅作用于图片列表，不持久化）。
 */
data class TempFilter(
    val tagNames: List<String> = emptyList(),
    val logic: FilterLogic = FilterLogic.OR,
    val mode: FilterMode = FilterMode.HIDE
) {
    val isActive: Boolean get() = tagNames.isNotEmpty()
}

// ══════════════════════════════════════
//  冲突检测
// ══════════════════════════════════════

/**
 * 检测两条规则是否冲突。
 *
 * 冲突定义：
 * 1. 两条都是 SHOW_ONLY，且标签集合有交集但不完全相同 → 冲突
 *    （例：[A] 说 A 就行，[A,B] 说 A+B 才行 → 口径不一致）
 * 2. 一条 SHOW_ONLY、一条 HIDE，且标签集合有交集 → 冲突
 *    （例：[A] 要看，[A] 要藏 → 矛盾）
 * 3. 两条都是 HIDE → 永远不冲突（都在藏，叠加只是多藏一批）
 */
fun rulesConflict(a: TagRule, b: TagRule): Boolean {
    val aSet = a.tagNames.toSet()
    val bSet = b.tagNames.toSet()
    val intersection = aSet.intersect(bSet)
    if (intersection.isEmpty()) return false // 管的不同标签，不冲突

    return when {
        a.mode == FilterMode.HIDE && b.mode == FilterMode.HIDE -> false
        a.mode != b.mode -> true // 一个要藏一个要显 → 冲突
        else -> {
            // 都是 SHOW_ONLY：有交集但不完全相同 → 冲突
            aSet != bSet
        }
    }
}

/**
 * 检测一组规则中是否有冲突。
 * @return 第一对冲突的规则索引，没有则返回 null
 */
fun findFirstConflict(rules: List<TagRule>): Pair<Int, Int>? {
    for (i in rules.indices) {
        for (j in i + 1 until rules.size) {
            if (rulesConflict(rules[i], rules[j])) return i to j
        }
    }
    return null
}
