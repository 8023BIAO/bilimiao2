# ===== 核心行为 =====
# 禁混淆（保留可读堆栈），允许优化
-dontobfuscate

# ===== 调试信息（崩溃定位必须） =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes *Annotation*

# ===== 应用业务代码 =====
-keep class com.a10miaomiao.** { *; }
-keep class cn.a10miaomiao.** { *; }

# Kotlin data class 关键方法（copy/componentN 等反射使用）
-keepclassmembers class com.a10miaomiao.** {
    synthetic <methods>;
}
-keepclassmembers class cn.a10miaomiao.** {
    synthetic <methods>;
}

# ===== B站 GRPC Protobuf 生成类 =====
-keep class bilibili.** { *; }
-keep class com.bapis.** { *; }

# ===== Kotlin 元数据 =====
-keep class kotlin.Metadata { *; }

# Kotlin 反射
-keep class kotlin.reflect.** { *; }

# Kotlin 2.x 编译器内部类
-keep class kotlin.collections.builders.** { *; }
-keep class kotlin.internal.** { *; }
-keep class kotlin.jvm.internal.** { *; }

# Kotlin Result / Continuation（协程恢复需要）
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Failure { *; }
-keep class kotlin.coroutines.Continuation { *; }

# ===== kotlinx.serialization =====
# 只需要保住运行库本身：
#  - @Serializable 类的 $serializer/Companion 由 kotlinx-serialization **自带的 consumer rules** 负责；
#  - 我们自家的 @Serializable 类（含导航路由页）都在下面
#    `-keep class com.a10miaomiao.**` / `cn.a10miaomiao.**` 的范围内，本来就不会被删。
# 2026-09-20 清理：原来这里还有两条 `@kotlinx.serialization.Serializable <fields>` 的过滤器 +
# 一个以它为条件的 -if —— **注解打在类上、不在字段上**，条件永远不成立，属于空规则，已删（不影响行为）。
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }

# ===== Compose (BOM 2026.05 + Kotlin Compose Compiler Plugin) =====
# 防止 R8 删除 @Composable 函数（Kotlin 2.x Compose compiler 生成新字节码模式）
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose compiler stability 推断相关
-keep class androidx.compose.runtime.internal.** { *; }
-keep class androidx.compose.runtime.snapshots.** { *; }

# ===== Navigation 2.9.8 (反射路由) =====
# 2026-09-20 清理：原来这里还有一条 `pages.**.PageConfig` —— `PageConfig` 是**函数/文件名**
# （common/mypage/PageConfig.kt），那个包下并不存在这个类，属于空规则，已删。
# 真正需要保的是"路由页"（@Serializable + ComposePage），见文件末尾那一组。
-keep class * implements androidx.navigation.NavArgs { *; }

# ===== 播放器 =====
-keep class com.shuyu.gsyvideoplayer.** { *; }
-keepclassmembers class * extends com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer {
    <init>(...);
}

# ===== 图片加载 (Glide) =====
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ===== DI (Kodein) =====
-keep class org.kodein.type.** { *; }
# TypeReference / TypeToken 子类：Kodein 靠它们的成员做类型查找，必须连成员一起保
# （2026-09-20 修正：原来只有 `-keep class * extends …`，没写 { *; } —— 只保住了类名）
-keep class * extends org.kodein.type.TypeReference { *; }
-keep class * extends org.kodein.type.TypeToken { *; }
# Kodein Compose 内联函数生成类（rememberInstance 等反射依赖）
-keep class org.kodein.di.compose.**$* { *; }
-keepclassmembers class org.kodein.di.compose.** {
    *** $inlined$*(...);
}
-dontwarn org.kodein.di.compose.**

# ===== UI 库 =====
-keep class com.kongzue.dialogx.** { *; }
-keep class com.mikaelzero.mojito.** { *; }
-keep class splitties.** { *; }
-keep class pbandk.** { *; }
-keep class com.materialkolor.** { *; }

# ===== Material 1.14.0 构造器（Splitties 反射调用） =====
-keepclassmembers class com.google.android.material.** {
    <init>(android.content.Context);
    <init>(android.content.Context, android.util.AttributeSet);
}

# ===== SafeParcel (序列化) =====
-keep class org.microg.safeparcel.** { *; }

# ===== PlaybackService（前台服务，系统反射恢复） =====
-keep class com.a10miaomiao.bilimiao.comm.delegate.player.PlaybackService { *; }

# ===== 通用 Android 组件 =====
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===== WebView JS 接口 =====
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ===== ViewModel DI 构造器（反射创建 diViewModel） =====
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(org.kodein.di.DI);
}

# ===== DataStore Preferences Key 对象 =====
-keep class com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences$** { *; }

# ===== Navigation @Serializable 路由类 && ComposePage =====
-keep class cn.a10miaomiao.bilimiao.compose.pages.** implements cn.a10miaomiao.bilimiao.compose.base.ComposePage { *; }
-keepclassmembers class cn.a10miaomiao.bilimiao.compose.pages.** {
    *** $serializer;
    *** Companion;
}

# ===== Http 响应实体（所有 entity 包） =====
-keep class com.a10miaomiao.bilimiao.comm.entity.** { *; }
-keepclassmembers class com.a10miaomiao.bilimiao.comm.entity.** {
    <fields>;
    <init>(...);
}

# ===== 静默警告 =====
# 说明（2026-09-20 复核）：这些都是**第三方库引用的可选依赖**（TLS 提供方 / OSGi 注解 / 脱糖用到
# java.* 之外的类型 / 库内部实现类），Android 上本来就不存在，不 -dontwarn 只会在编译期刷一片警告。
# 想进一步收紧（只保留真正触发警告的那几条）必须在**有编译环境**的地方做 —— 删多了会直接编译失败，
# 所以这里保持现状，只补注释。
-dontwarn okhttp3.internal.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn com.google.protobuf.**
-dontwarn pbandk.**
# jUPnP 投屏库引用了 OSGi 注解（Android 不需要）
-dontwarn org.jupnp.**
-dontwarn org.osgi.**
# Kotlin 2.x / OkHttp 5.x 内部类
-dontwarn kotlin.collections.builders.**
-dontwarn kotlin.internal.**
# Core Library Desugaring
-dontwarn java.lang.invoke.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn jdk.internal.**
# Glide 4.16
-dontwarn com.bumptech.glide.gifdecoder.**
# Compose 2026 BOM
-dontwarn androidx.compose.runtime.internal.**
# Activity 1.13.0 internal
-dontwarn androidx.activity.compose.PredictiveBackHandlerKt
