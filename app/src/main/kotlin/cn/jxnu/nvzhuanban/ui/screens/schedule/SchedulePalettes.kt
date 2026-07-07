package cn.jxnu.nvzhuanban.ui.screens.schedule

import androidx.compose.ui.graphics.Color
import cn.jxnu.nvzhuanban.data.model.Course
import cn.jxnu.nvzhuanban.data.storage.SchedulePalette

/**
 * 课表课程卡的可选配色方案。枚举与持久化在
 * [cn.jxnu.nvzhuanban.data.storage.SchedulePalette] / [cn.jxnu.nvzhuanban.data.storage.ThemePrefs]，
 * 这里只放 UI 侧的展示名与 12 色色板。
 *
 * **硬约束：每个方案的所有颜色都必须保证其上白色 9sp 小字对比度 ≥ 4.5:1（WCAG AA）**——
 * 课程卡文字始终是白色（[CourseCard]），"上课中/下节"角标又反过来把色值当白底上的文字色用，
 * 两个方向的对比度是同一个比值。新增/调整任何色值前先过 SchedulePaletteContrastTest
 * （JVM 单测，CI 强制），浅色 300 系 + 白字曾低至 1.7:1，别回去。
 */
internal val SchedulePalette.label: String
    get() = when (this) {
        SchedulePalette.CLASSIC -> "经典明快"
        SchedulePalette.MORANDI -> "莫兰迪雾调"
        SchedulePalette.OCEAN -> "海雾蓝调"
        SchedulePalette.SUNSET -> "暖阳落霞"
        SchedulePalette.FOREST -> "黛绿山野"
    }

internal val SchedulePalette.description: String
    get() = when (this) {
        SchedulePalette.CLASSIC -> "高饱和多色，课程之间区分度最强"
        SchedulePalette.MORANDI -> "低饱和灰调，安静柔和不刺眼"
        SchedulePalette.OCEAN -> "蓝绿同族色，整页清爽统一"
        SchedulePalette.SUNSET -> "红橙暖调，温暖有活力"
        SchedulePalette.FOREST -> "绿系自然色，护眼沉稳"
    }

internal val SchedulePalette.colors: List<Color>
    get() = when (this) {
        // Material 700/800 档，即 v1.3.0 之前唯一的一套 COURSE_PALETTE，保持默认不动老用户
        SchedulePalette.CLASSIC -> listOf(
            Color(0xFFD32F2F), // Red 700
            Color(0xFF7B1FA2), // Purple 700
            Color(0xFF303F9F), // Indigo 700
            Color(0xFF0277BD), // Light Blue 800
            Color(0xFF00796B), // Teal 700
            Color(0xFF2E7D32), // Green 800
            Color(0xFFBF360C), // Deep Orange 900
            Color(0xFF6D4C41), // Brown 600
            Color(0xFF546E7A), // Blue Grey 600
            Color(0xFFC2185B), // Pink 700
            Color(0xFF5E35B1), // Deep Purple 600
            Color(0xFF1976D2), // Blue 700
        )
        SchedulePalette.MORANDI -> listOf(
            Color(0xFF8C5A5A), // 灰玫红
            Color(0xFF7A6784), // 灰藕紫
            Color(0xFF5C6B8A), // 雾蓝灰
            Color(0xFF4F7A85), // 灰青
            Color(0xFF5F7D6B), // 灰豆绿
            Color(0xFF7C7455), // 灰橄榄
            Color(0xFF96674C), // 灰陶土
            Color(0xFF816A5B), // 灰驼
            Color(0xFF6E7276), // 岩灰
            Color(0xFF8A5E71), // 灰紫红
            Color(0xFF665E7E), // 暮紫
            Color(0xFF54728C), // 灰海蓝
        )
        SchedulePalette.OCEAN -> listOf(
            Color(0xFF0D5C8C), // 深海蓝
            Color(0xFF00697C), // 深青
            Color(0xFF2A5CAA), // 群青
            Color(0xFF00758F), // 孔雀蓝
            Color(0xFF4A5FA5), // 蓝紫
            Color(0xFF006A5B), // 深松石
            Color(0xFF13698A), // 远洋
            Color(0xFF32617D), // 雾港蓝
            Color(0xFF008577), // 深湖绿
            Color(0xFF58599E), // 鸢尾蓝
            Color(0xFF11637C), // 深潟湖
            Color(0xFF3D6B99), // 海雾
        )
        SchedulePalette.SUNSET -> listOf(
            Color(0xFFC62828), // 落日红
            Color(0xFFAD4E00), // 琥珀橙
            Color(0xFFB03A5B), // 霞粉
            Color(0xFF8D5524), // 焦糖
            Color(0xFFA5433A), // 赤陶
            Color(0xFF934D91), // 暮紫红
            Color(0xFFB7472A), // 橘红
            Color(0xFF7E5738), // 暖褐
            Color(0xFFC13B13), // 炽橙
            Color(0xFF9C4A62), // 干玫瑰
            Color(0xFF8A5A2B), // 赭金
            Color(0xFFAA3F72), // 紫霞
        )
        SchedulePalette.FOREST -> listOf(
            Color(0xFF2F6B3A), // 松绿
            Color(0xFF456E28), // 苔绿
            Color(0xFF1F6E5A), // 杉青
            Color(0xFF5B6B2F), // 橄榄
            Color(0xFF2E7D62), // 竹绿
            Color(0xFF6B5B33), // 山核桃
            Color(0xFF39705F), // 黛青
            Color(0xFF4E6E44), // 灰松
            Color(0xFF276B49), // 翠涧
            Color(0xFF70663A), // 秋茅
            Color(0xFF32665F), // 苍苔
            Color(0xFF587042), // 山岚绿
        )
    }

/**
 * 通过课程名称的稳定 hash 派生颜色，让同名课程颜色一致；同一门课在不同方案下
 * 落在相同下标（所有方案都是 12 色），切换方案不会打乱"哪两门课撞色"的相对关系。
 */
internal fun courseColor(course: Course, palette: SchedulePalette): Color {
    val colors = palette.colors
    // 用 and Int.MAX_VALUE 取非负：避免 hashCode == Int.MIN_VALUE 时 `-it` 仍为负 → % 出负 index → 越界
    val idx = (course.name.hashCode() and Int.MAX_VALUE) % colors.size
    return colors[idx]
}
