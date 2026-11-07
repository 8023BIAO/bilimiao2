package cn.a10miaomiao.bilimiao.compose.components.image

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 长图判定阈值：高/宽 > 2.6 视为长图（产品决定（2026-11-07），与 PiliPlus 的 imgMaxRatio 一致）。
 *
 * 普通图（高/宽 ≤ 2.6）的显示/缩放/手势逻辑必须保持原样，任何新增分支都以本判定为唯一分叉条件。
 */
const val LONG_IMAGE_ASPECT_RATIO = 2.6f

// EGL 查询失败时的保守上限：8192 是绝大多数 2016 年后 Android 设备的 GL_MAX_TEXTURE_SIZE 下限
private const val DEFAULT_GPU_MAX_TEXTURE_SIZE = 8192

// 纹理上限允许的上下限：下限沿用原 4096 的图床请求尺寸，上限是当前图床可接受的最高请求高度
private const val MIN_TEXTURE_SIZE = 4096
private const val MAX_TEXTURE_SIZE = 16384

// 长图预览的像素预算：沿用改造前 4096x4096 的防护量级（约 67MB bitmap）。
// 原图直链与缩图请求**两条分支**都受它约束：
// - 原图直链：高度未超纹理上限时，按原图像素数直接拦；
// - 缩图请求：图床只缩不放，请求框 ≥ 原图时会原样返回，所以要先按两种输出形态
//   （「请求宽 × 上限高」与「请求宽² × 高宽比」）反推最大请求宽，再发请求。
private const val MAX_ORIGINAL_PIXEL_COUNT = 4096L * 4096L

// 进程内缓存：0 表示尚未查询
@Volatile
private var cachedMaxTextureSizePx = 0

// 串行化首次查询，避免多个长图页同时创建 EGL 上下文
private val gpuMaxTextureSizeLock = Any()

/**
 * 是否为长图：宽、高都有效（> 0）且高/宽 > [LONG_IMAGE_ASPECT_RATIO]。
 */
fun isLongImage(width: Float, height: Float): Boolean {
    return width > 0f && height > 0f && height / width > LONG_IMAGE_ASPECT_RATIO
}

/**
 * 是否为长图（[isLongImage] 的 Size 版本；Size.Zero 等无效尺寸一律返回 false）。
 */
fun Size.isLongImage(): Boolean = isLongImage(width = width, height = height)

/**
 * 查询设备 GPU 最大纹理尺寸（GL_MAX_TEXTURE_SIZE），进程内缓存。
 *
 * 图床的 `@<宽>w_<高>h` 后缀是等比缩图，超过 GPU 纹理上限的 bitmap 无法上传成纹理（绘制会失败/空白）。
 * 这里用 1x1 pbuffer + OpenGL ES 2.0 上下文查询真实上限，失败时保守回落 8192；
 * 再按 ActivityManager.memoryClass 收紧：低内存机型往往同时限制纹理尺寸，避免画布峰值内存过高。
 *
 * 结果范围 [4096, 16384]：4096 是长图改造前所有 hdslb 图的请求高度（不会更差），
 * 16384 是图床可接受的最高请求高度。
 */
suspend fun gpuMaxTextureSizePx(context: Context): Int {
    cachedMaxTextureSizePx.takeIf { it > 0 }?.let { return it }
    return withContext(Dispatchers.IO) {
        synchronized(gpuMaxTextureSizeLock) {
            cachedMaxTextureSizePx.takeIf { it > 0 } ?: run {
                val gpuLimit = runCatching { queryGpuMaxTextureSize() }
                    .getOrDefault(DEFAULT_GPU_MAX_TEXTURE_SIZE)
                val memoryLimit = when {
                    activityMemoryClass(context) <= 192 -> 4096
                    activityMemoryClass(context) <= 256 -> 8192
                    else -> 16384
                }
                min(gpuLimit, memoryLimit)
                    .coerceIn(MIN_TEXTURE_SIZE, MAX_TEXTURE_SIZE)
                    .also { cachedMaxTextureSizePx = it }
            }
        }
    }
}

/**
 * 当前进程的 ActivityManager.memoryClass（MB），取不到时按 512 处理（不做额外收紧）。
 */
private fun activityMemoryClass(context: Context): Int {
    return runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
    }.getOrDefault(512)
}

/**
 * 创建一个 1x1 的 pbuffer 上下文查询 GL_MAX_TEXTURE_SIZE。
 * 查询完立即销毁全部 EGL 资源，不把上下文留给后续绘制使用。
 */
private fun queryGpuMaxTextureSize(): Int {
    var display: EGLDisplay? = null
    var context: EGLContext? = null
    var surface: EGLSurface? = null
    try {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay 失败" }
        display = eglDisplay

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize 失败" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                configs.size,
                configCount,
                0,
            ) && configCount[0] > 0
        ) { "eglChooseConfig 失败" }
        val eglConfig = configs[0] ?: error("未找到可用的 EGLConfig")

        val eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
        context = eglContext

        val eglSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface 失败" }
        surface = eglSurface

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent 失败"
        }
        val maxTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        return maxTextureSize[0]
    } finally {
        display?.let { eglDisplay ->
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            surface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            context?.let { EGL14.eglDestroyContext(eglDisplay, it) }
            EGL14.eglTerminate(eglDisplay)
        }
    }
}

/**
 * 计算长图预览地址：按屏幕宽度、GPU 纹理上限与像素预算向图床请求等比缩图。
 *
 * - 原图高度不超过纹理上限、且像素总数不超过 [MAX_ORIGINAL_PIXEL_COUNT]：直接返回原图
 *   （图床只缩不放，原图最清晰）；
 * - 否则请求 `@<宽>w_<上限>h`：高度不超过上限，宽度取屏幕宽、原图宽与像素预算反推的最大
 *   请求宽三者最小，避免 19:1 这类长图被统一压到 4096 高度后再被铺屏拉宽（约 6 倍拉伸），
 *   也避免超宽长图（如横屏 2800×16000）解码出 170MB 级 bitmap。
 *
 * 两条分支的输出都不超过 [MAX_ORIGINAL_PIXEL_COUNT]（约 67MB bitmap）。
 * 仅用于 hdslb 图床且 URL 本身不带 `@` 参数的情况，调用方负责判断。
 */
internal fun longImagePreviewUrl(
    originalUrl: String,
    imageWidth: Float,
    imageHeight: Float,
    screenWidthPx: Int,
    maxTextureSizePx: Int,
): String {
    val originalPixelCount = imageWidth.toLong() * imageHeight.toLong()
    if (imageHeight <= maxTextureSizePx && originalPixelCount <= MAX_ORIGINAL_PIXEL_COUNT) {
        return originalUrl
    }
    val ratio = imageHeight / imageWidth
    val heightLimitedWidth = floor(maxTextureSizePx / ratio).toInt()
    // 像素预算：缩图分支同样不能超过原图守卫的 4096² 量级。
    // capHeightLimitedWidth 覆盖「高度顶格」的输出（宽 × 上限高）；
    // ratioLimitedWidth 覆盖「只按宽缩」的输出（宽² × 高宽比），由 宽² ≤ 预算/高宽比 反推。
    val capHeightLimitedWidth = (MAX_ORIGINAL_PIXEL_COUNT / maxTextureSizePx).toInt()
    val ratioLimitedWidth = floor(sqrt(MAX_ORIGINAL_PIXEL_COUNT / ratio)).toInt()
    val requestWidth = min(imageWidth.toInt(), min(screenWidthPx, heightLimitedWidth))
        .coerceAtMost(capHeightLimitedWidth)
        .coerceAtMost(ratioLimitedWidth)
        .coerceAtLeast(1)
    return originalUrl + "@${requestWidth}w_${maxTextureSizePx}h"
}
