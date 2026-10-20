package com.lapseclock.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDateTime

/**
 * 时钟视觉主题：把原先硬编码在 ClockScreen 里的三个颜色常量收拢成可替换的一整套。
 *
 * 分三个层次决定最终外观（优先级由低到高）：
 * 1. 基础皮肤：用户手选，或自动模式下按时段选
 * 2. 天气色调：只覆盖 accent，不改底色（阴雨天不该整套换肤）
 * 3. 节日色调：覆盖 accent；自动模式下属实节日时再微调底色
 *
 * 这样节日/天气/时间三种信号互不打架，加新信号只需往对应层塞一条规则。
 */
data class ClockTheme(
    val id: String,
    val name: String,
    /** 背景色 */
    val bg: Color,
    /** 主数字颜色 */
    val main: Color,
    /** 次要信息（日期、单位、进度槽） */
    val dim: Color,
    /** 强调色：节日高亮、进度线、角标 */
    val accent: Color,
    /** 数字字重，皮肤之间用来拉开气质差异 */
    val weight: FontWeight = FontWeight.Thin,
)

object Themes {

    const val AUTO = "auto"

    val ink = ClockTheme(
        id = "ink",
        name = "墨白",
        bg = Color(0xFF000000),
        main = Color(0xFFF2F2F2),
        dim = Color(0xFF8A8A8A),
        accent = Color(0xFF6FA8FF),
    )

    val warm = ClockTheme(
        id = "warm",
        name = "暖夜",
        bg = Color(0xFF0C0906),
        main = Color(0xFFF3E7D6),
        dim = Color(0xFF8A7C6B),
        accent = Color(0xFFE8A33D),
    )

    val celadon = ClockTheme(
        id = "celadon",
        name = "青瓷",
        bg = Color(0xFF06100C),
        main = Color(0xFFDCEFE6),
        dim = Color(0xFF6F8F83),
        accent = Color(0xFF4FD1A5),
    )

    val film = ClockTheme(
        id = "film",
        name = "胶片",
        bg = Color(0xFF0F0F11),
        main = Color(0xFFEDE6D8),
        dim = Color(0xFF8A857A),
        accent = Color(0xFFC4553B),
        weight = FontWeight.Light,
    )

    val cyber = ClockTheme(
        id = "cyber",
        name = "赛博",
        bg = Color(0xFF04060D),
        main = Color(0xFF9BE7FF),
        dim = Color(0xFF4D7E96),
        accent = Color(0xFFFF4D9D),
        weight = FontWeight.Light,
    )

    /** 用户可手选的皮肤（不含自动） */
    val fixed: List<ClockTheme> = listOf(ink, warm, celadon, film, cyber)

    /** 设置界面的选项：自动 + 各固定皮肤 */
    val choices: List<ClockTheme> = listOf(
        ink.copy(id = AUTO, name = "自动"),
    ) + fixed

    fun byId(id: String): ClockTheme? = fixed.firstOrNull { it.id == id }

    /** 双击切皮肤的循环顺序：自动 → 墨白 → 暖夜 → 青瓷 → 胶片 → 赛博 → 自动 */
    fun nextChoice(currentId: String): ClockTheme {
        val idx = choices.indexOfFirst { it.id == currentId }
        val next = if (idx < 0) 0 else (idx + 1) % choices.size
        return choices[next]
    }
}

// ---------------------------------------------------------------- 时段层

private val dawn = ClockTheme(
    id = "dawn",
    name = "黎明",
    bg = Color(0xFF110B15),
    main = Color(0xFFEADDF1),
    dim = Color(0xFF8B7A96),
    accent = Color(0xFFD89BB5),
)

private val dusk = ClockTheme(
    id = "dusk",
    name = "黄昏",
    bg = Color(0xFF150B05),
    main = Color(0xFFF6E1C9),
    dim = Color(0xFF96795C),
    accent = Color(0xFFFF9E4D),
)

private val night = ClockTheme(
    id = "night",
    name = "夜蓝",
    bg = Color(0xFF050912),
    main = Color(0xFFD6E1F5),
    dim = Color(0xFF6B7A94),
    accent = Color(0xFF6E8FE0),
)

/**
 * 时段边界写死而不去查真实日出日落：
 * 这是摆在室内工位上的钟，看不到日出日落，多一次网络请求只换来几分钟的精度，不划算。
 */
private fun timeOfDayTheme(now: LocalDateTime): ClockTheme = when (now.hour) {
    in 5..6 -> dawn
    in 7..16 -> Themes.ink
    in 17..18 -> dusk
    else -> night
}

// ---------------------------------------------------------------- 天气层

/** 只换 accent：阴雨天把强调色压冷，底色交给时段，避免一天里反复整套换肤 */
private fun weatherTint(text: String?): Color? {
    val t = text ?: return null
    return when {
        t.contains("雪") -> Color(0xFFCFE3F5)
        t.contains("雨") || t.contains("雷") -> Color(0xFF5B9BD5)
        t.contains("雾") || t.contains("霾") || t.contains("沙") || t.contains("尘") -> Color(0xFF9AA3AB)
        t.contains("阴") -> Color(0xFF8E97A3)
        else -> null
    }
}

// ---------------------------------------------------------------- 节日层

private data class FestivalLook(val accent: Color, val bg: Color? = null)

private val festivalKeywords: List<Pair<List<String>, FestivalLook>> = listOf(
    listOf("春节", "除夕", "元宵", "小年", "腊八") to FestivalLook(Color(0xFFFF6B4A), Color(0xFF120404)),
    listOf("中秋") to FestivalLook(Color(0xFFFFD98E), Color(0xFF0B0D14)),
    listOf("国庆") to FestivalLook(Color(0xFFFF5252), Color(0xFF120606)),
    listOf("端午") to FestivalLook(Color(0xFF7ED957), Color(0xFF04100A)),
    listOf("清明") to FestivalLook(Color(0xFF9BA8A2), Color(0xFF080C0B)),
    listOf("元旦") to FestivalLook(Color(0xFFFFB347), null),
    listOf("七夕") to FestivalLook(Color(0xFFF49AC1), null),
    listOf("重阳") to FestivalLook(Color(0xFFE0A458), null),
    listOf("圣诞") to FestivalLook(Color(0xFF5BD97A), Color(0xFF04120A)),
    listOf("劳动节", "五一") to FestivalLook(Color(0xFFFF8A5B), null),
)

private fun festivalLook(special: String?): FestivalLook? {
    val s = special ?: return null
    return festivalKeywords.firstOrNull { (keys, _) -> keys.any { s.contains(it) } }?.second
}

// ---------------------------------------------------------------- 解析器

/**
 * 唯一的主题出口。所有界面都从这里取 [ClockTheme]，不要各自算颜色。
 *
 * @param chosenId 设置里选的皮肤 id，[Themes.AUTO] 表示跟随时段自动
 * @param weatherText 和风天气的实况文本，未配置天气时为 null
 * @param special 当天节日/节气文本（DayInfo.specialText）
 */
fun resolveClockTheme(
    chosenId: String,
    now: LocalDateTime,
    weatherText: String?,
    special: String?,
): ClockTheme {
    val manual = Themes.byId(chosenId)
    val auto = manual == null
    var theme = manual ?: timeOfDayTheme(now)

    weatherTint(weatherText)?.let { theme = theme.copy(accent = it) }

    festivalLook(special)?.let { look ->
        theme = theme.copy(accent = look.accent)
        // 底色只在自动模式下让节日接管，手选了皮肤就尊重用户的选择
        if (auto) look.bg?.let { theme = theme.copy(bg = it) }
    }

    return theme
}

/** 主题当前是跟随时段还是用户锁定，用于第二屏和设置里的说明文字 */
fun themeModeLabel(chosenId: String, theme: ClockTheme): String =
    if (Themes.byId(chosenId) == null) "自动 · ${theme.name}" else theme.name
