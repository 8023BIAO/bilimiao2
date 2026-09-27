package cn.a10miaomiao.bilimiao.compose.pages.setting.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilimiao.compose.components.dialogs.OverlayAlertDialog
import cn.a10miaomiao.bilimiao.compose.customPaletteOverride
import com.a10miaomiao.bilimiao.comm.datastore.SettingConstants
import com.materialkolor.dynamicColorScheme

/**
 * 自定义主题调色弹窗（主题设置页第 11 项"自定义"）。
 *
 * 三个颜色（用户嘴里的"三种"）分别对应 Material 配色的三条调色板：
 *  - **主色**：整套配色的种子 —— primary 一族、以及所有中性色（surface/background/outline）都由它推导；
 *  - **副色**：secondary 一族（次要按钮、次要标签、部分选中态）；
 *  - **点缀色**：tertiary 一族（对比/强调位置，比如"换个口味"的按钮、进度条、徽标）。
 * 副色 / 点缀色默认"跟随主色"，此时**完全不覆盖**调色板，配色与本功能上线前逐位相同。
 *
 * 交互：
 *  - 上面三个 chip 选通道（每个 chip 自带色块，三种颜色同屏可见），点谁调谁；
 *  - 一条 HSV 滑块 + 一个十六进制输入框精调 —— 滑块直观、十六进制用来"照着抄一个已知色值"，
 *    两条路都能到达任意颜色（HSV 能覆盖全部 16,777,216 色）；
 *  - 下面是实时预览：直接画 materialkolor **生成之后**的真实槽位色，
 *    所以"预览里看着能看清"就等于保存后能看清。
 *
 * 弹窗基建沿用仓库既有约定：OverlayAlertDialog（窗口铺满、卡片高度封顶、正文可滚、
 * 底部按钮固定），按钮槽位与 DpiSettingDialog/TextIntPreference 一致：dismissButton=取消、confirmButton=保存。
 *
 * @param initialPrimary 打开时的主色（没保存过自定义时由调用方传"当前主题色"）
 * @param initialSecondary 打开时的副色；与 initialPrimary 相等 = 跟随主色
 * @param initialTertiary 打开时的点缀色；与 initialPrimary 相等 = 跟随主色
 * @param darkTheme 当前是不是深色主题（预览要按同一明暗生成，否则预览和实际不一致）
 */
@Composable
fun CustomThemeColorDialog(
    initialPrimary: Int,
    initialSecondary: Int,
    initialTertiary: Int,
    darkTheme: Boolean,
    onDismiss: () -> Unit,
    onSave: (primary: Int, secondary: Int, tertiary: Int) -> Unit,
) {
    // 三个通道各存一份 HSV，而不是存 ARGB Int：
    // 灰阶（S=0）和纯黑（V=0）是反解不出色相的 —— 存 Int 的话"饱和度拖到 0 再拖回来"色相就丢了，
    // 所以把 HSV 当唯一数据源，ARGB 每次按键算出来。
    var primaryHsv by remember { mutableStateOf(HsvColor.fromArgb(initialPrimary)) }
    var secondaryHsv by remember { mutableStateOf(HsvColor.fromArgb(initialSecondary)) }
    var tertiaryHsv by remember { mutableStateOf(HsvColor.fromArgb(initialTertiary)) }
    // "跟随主色"是独立开关而不是"值恰好相等"：开着的时候改主色，副色/点缀色要跟着动
    // （否则用户先选跟随、再改主色，副色会悄悄变成一个"等于旧主色"的固定覆盖色 —— 完全不是他要的）
    var secondaryFollowing by remember { mutableStateOf(initialSecondary == initialPrimary) }
    var tertiaryFollowing by remember { mutableStateOf(initialTertiary == initialPrimary) }
    var channel by remember { mutableStateOf(ThemeColorChannel.Primary) }

    val primary = primaryHsv.toArgb()
    val secondary = if (secondaryFollowing) primary else secondaryHsv.toArgb()
    val tertiary = if (tertiaryFollowing) primary else tertiaryHsv.toArgb()

    val activeFollowing = when (channel) {
        ThemeColorChannel.Primary -> false
        ThemeColorChannel.Secondary -> secondaryFollowing
        ThemeColorChannel.Tertiary -> tertiaryFollowing
    }
    // 当前通道"显示中"的 HSV：跟随主色时直接显示主色的 HSV（滑块、色相条都跟着主色走）
    val activeHsv = when (channel) {
        ThemeColorChannel.Primary -> primaryHsv
        ThemeColorChannel.Secondary -> if (secondaryFollowing) primaryHsv else secondaryHsv
        ThemeColorChannel.Tertiary -> if (tertiaryFollowing) primaryHsv else tertiaryHsv
    }
    val activeColor = activeHsv.toArgb()
    // 十六进制输入框的文本。remember(channel)：换通道就换成那个通道的色值；
    // 拖滑块 / 点"跟随主色"时由 updateActive 同步；唯独用户打字时不回写 ——
    // 否则删掉前导 '#' 会被立刻补回来，根本没法编辑。
    var hexText by remember(channel) { mutableStateOf(argbToHex(activeColor)) }
    val hexInvalid = hexText.isNotBlank() && parseHexColor(hexText) == null

    /**
     * 把当前通道改成 hsv。
     * @param syncHexText 滑块拖动/跟随主色要同步输入框；十六进制输入自身解析出来的值不要同步（否则和手打字打架）
     */
    fun updateActive(hsv: HsvColor, syncHexText: Boolean) {
        when (channel) {
            ThemeColorChannel.Primary -> primaryHsv = hsv
            ThemeColorChannel.Secondary -> {
                secondaryHsv = hsv
                secondaryFollowing = false // 一旦手动调过，就不再跟随主色
            }
            ThemeColorChannel.Tertiary -> {
                tertiaryHsv = hsv
                tertiaryFollowing = false
            }
        }
        if (syncHexText) hexText = argbToHex(hsv.toArgb())
    }

    // 预览用的是**真正会生效**的那套配色：materialkolor 拿到的是种子色，
    // 落到 primary/secondary/... 上的其实是"色调映射之后"的颜色
    // （比如纯白主色实际得到浅灰，而不是白底白字）。
    // 这里复刻 BilimiaoTheme.appColorScheme 的规则（连"跟随主色不覆盖"都共用同一个函数），
    // 保证"预览所见 = 保存后所得"。remember：拖滑块时别每帧重算整套配色。
    val previewScheme = remember(primary, secondary, tertiary, darkTheme) {
        dynamicColorScheme(
            seedColor = Color(primary),
            isDark = darkTheme,
            isAmoled = true, // 与 appColorScheme 保持一致（AMOLED 深色下 surface 是纯黑）
            secondary = customPaletteOverride(secondary, primary, SettingConstants.THEME_TYPE_CUSTOM),
            tertiary = customPaletteOverride(tertiary, primary, SettingConstants.THEME_TYPE_CUSTOM),
        )
    }

    OverlayAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "自定义主题色",
                fontWeight = FontWeight.W700,
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---------- 通道选择 ----------
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeColorChannel.entries.forEach { item ->
                        ColorChannelChip(
                            label = item.label,
                            color = Color(
                                when (item) {
                                    ThemeColorChannel.Primary -> primary
                                    ThemeColorChannel.Secondary -> secondary
                                    ThemeColorChannel.Tertiary -> tertiary
                                }
                            ),
                            selected = item == channel,
                            onClick = { channel = item },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // ---------- 当前色值 / 跟随主色 ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(activeColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = argbToHex(activeColor),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (channel != ThemeColorChannel.Primary) {
                        if (activeFollowing) {
                            // 跟随中：只提示，不给按钮 —— 想脱离跟随，拖一下滑块或改一下十六进制就自动脱离
                            Text(
                                text = "跟随主色",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    // 跟随主色：把这一路设回"跟着主色走"，滑块/输入框立刻显示主色
                                    when (channel) {
                                        ThemeColorChannel.Secondary -> secondaryFollowing = true
                                        ThemeColorChannel.Tertiary -> tertiaryFollowing = true
                                        ThemeColorChannel.Primary -> Unit
                                    }
                                    hexText = argbToHex(primary)
                                },
                            ) {
                                Text("跟随主色", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // ---------- 十六进制输入（精确到 1/255 的兜底通道）----------
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { newText ->
                        hexText = newText
                        // 只在解析成功时改颜色：输入到一半（"#FF0"）不该让配色乱跳
                        parseHexColor(newText)?.let { updateActive(HsvColor.fromArgb(it), syncHexText = false) }
                    },
                    singleLine = true,
                    isError = hexInvalid,
                    label = { Text("十六进制") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (hexInvalid) {
                        "颜色格式不对：认 #RGB / #RRGGBB / #AARRGGBB"
                    } else {
                        "支持 #RGB / #RRGGBB / #AARRGGBB（主题色一律不透明，alpha 按 FF 处理）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hexInvalid) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))

                // ---------- HSV 三滑块 ----------
                HsvSlider(
                    label = "色相",
                    value = activeHsv.hue,
                    valueRange = 0f..360f,
                    // 色相是环形的，写 360° 而不是 0°，免得"拖到头"看起来没反应
                    valueText = "${activeHsv.hue.toInt()}°",
                    trackBrush = Brush.horizontalGradient(HUE_COLORS),
                    onValueChange = { updateActive(activeHsv.copy(hue = it), syncHexText = true) },
                )
                HsvSlider(
                    label = "饱和度",
                    value = activeHsv.saturation,
                    valueRange = 0f..1f,
                    valueText = "${(activeHsv.saturation * 100).toInt()}%",
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            Color(activeHsv.copy(saturation = 0f).toArgb()),
                            Color(activeHsv.copy(saturation = 1f).toArgb()),
                        )
                    ),
                    onValueChange = { updateActive(activeHsv.copy(saturation = it), syncHexText = true) },
                )
                HsvSlider(
                    label = "明度",
                    value = activeHsv.value,
                    valueRange = 0f..1f,
                    valueText = "${(activeHsv.value * 100).toInt()}%",
                    trackBrush = Brush.horizontalGradient(
                        listOf(
                            Color(activeHsv.copy(value = 0f).toArgb()),
                            Color(activeHsv.copy(value = 1f).toArgb()),
                        )
                    ),
                    onValueChange = { updateActive(activeHsv.copy(value = it), syncHexText = true) },
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "预览（保存后的实际效果）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ThemePreviewCard(previewScheme)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(primary, secondary, tertiary) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** 调色通道：主色 / 副色 / 点缀色 */
private enum class ThemeColorChannel(val label: String) {
    Primary("主色"),
    Secondary("副色"),
    Tertiary("点缀色"),
}

/** 色相条的彩虹渐变（红→黄→绿→青→蓝→品→红，首尾同色所以接得上） */
private val HUE_COLORS = listOf(
    Color(0xFFFF0000.toInt()),
    Color(0xFFFFFF00.toInt()),
    Color(0xFF00FF00.toInt()),
    Color(0xFF00FFFF.toInt()),
    Color(0xFF0000FF.toInt()),
    Color(0xFFFF00FF.toInt()),
    Color(0xFFFF0000.toInt()),
)

/** 通道 chip：色块 + 名字，选中时加粗描边 */
@Composable
private fun ColorChannelChip(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * 一条带渐变底的滑块（色相 / 饱和度 / 明度通用）。
 *
 * 渐变条是**画在 Slider 底下**的，同时把 M3 自带轨道设成透明让渐变露出来 ——
 * 比自绘 Slider 稳（不吃 M3 的 API 变更），也比"再放一根色条在滑块上面"直观。
 */
@Composable
private fun HsvSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    trackBrush: Brush,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(trackBrush)
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}

/**
 * 实时预览：画的是这套配色的**真实槽位**（不是用户手里的原始色）。
 * 挑了最容易出问题的几对：primary/onPrimary（应用栏）、secondary|tertiary 及其 container（按钮/标签）、
 * surfaceContainer/onSurface（卡片正文）—— 改完颜色一眼就能看出有没有看不清的地方。
 */
@Composable
private fun ThemePreviewCard(scheme: ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        // 示例"应用栏"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.primary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "主题预览",
                color = scheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            PreviewDot(scheme.primaryContainer)
            Spacer(Modifier.width(6.dp))
            PreviewDot(scheme.inversePrimary)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewChip("副色", scheme.secondary, scheme.onSecondary)
                PreviewChip("点缀", scheme.tertiary, scheme.onTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreviewChip("副容器", scheme.secondaryContainer, scheme.onSecondaryContainer)
                PreviewChip("点缀容器", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }
            Surface(
                color = scheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, scheme.outline),
            ) {
                Text(
                    text = "正文示例（surfaceContainer / onSurface）",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewDot(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun PreviewChip(label: String, container: Color, onContainer: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = onContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// =====================================================================================
// 下面是与 Compose 无关的纯函数（HSV ↔ ARGB、十六进制解析）：
// 刻意不用 android.graphics.Color —— 纯 Kotlin 实现才能在 PC 上直接跑数值自测，
// 而且这些换算没有任何平台差异。
// =====================================================================================

/** 不透明色的 HSV 表示：色相 [0,360)、饱和度 [0,1]、明度 [0,1] */
internal data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
) {
    /** HSV → 0xFFRRGGBB */
    fun toArgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** 0xFFRRGGBB（alpha 忽略）→ HSV；纯灰阶的 hue 定为 0（重新调饱和度时从红色开始，符合直觉） */
        fun fromArgb(argb: Int): HsvColor {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
            val s = if (max == 0f) 0f else d / max
            return HsvColor(((h % 360f) + 360f) % 360f, s, max)
        }
    }
}

/** 0xFFRRGGBB → "#RRGGBB"。alpha 恒为 FF，不显示（输入时仍然认 8 位写法） */
internal fun argbToHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * 十六进制文本 → 0xFFRRGGBB；解析不出来返回 null（调用处据此标红，但**不改**当前颜色）。
 *
 * 认这几种写法（有没有前导 # 都行，也认 0x 前缀，大小写不限）：#RGB、#RRGGBB、#AARRGGBB。
 * alpha 一律按 FF 处理：主题色是不透明的种子色，带 alpha 只会让色调映射和"保存后到底啥样"难以预期。
 */
internal fun parseHexColor(text: String): Int? {
    val body = text.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    if (body.length != 3 && body.length != 6 && body.length != 8) return null
    if (body.any { it.digitToIntOrNull(16) == null }) return null
    val rgb = when (body.length) {
        3 -> body.map { "$it$it" }.joinToString("") // #ABC → #AABBCC
        8 -> body.substring(2)                      // 丢掉 alpha
        else -> body
    }
    return 0xFF000000.toInt() or rgb.toLong(16).toInt()
}
