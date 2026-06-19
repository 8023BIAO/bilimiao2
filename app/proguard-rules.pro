# ===== 核心行为 =====
# 禁混淆（保留可读堆栈），允许优化
-dontobfuscate

# ===== 调试信息（崩溃定位必须） =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keep class kotlin.Metadata { *; }

# Kotlin 反射
-keep class kotlin.reflect.** { *; }
-keep class kotlin.coroutines.** { *; }

# ===== Kotlin 2.3.x 编译器内部类 =====
-keep class kotlin.collections.builders.** { *; }
-keep class kotlin.internal.** { *; }
-keep class kotlin.jvm.internal.** { *; }

# Kotlin Result / Continuation（协程恢复需要）
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Failure { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlinx.coroutines.** { *; }

# kotlinx.datetime / kotlinx.io（Kotlin 2.x 新增核心库）
-keep class kotlinx.datetime.** { *; }
-keep class kotlinx.io.** { *; }

# ===== kotlinx.serialization =====
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
# 保护 @Serializable 类的 $serializer 伴生对象
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-if class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class <1>$serializer {
    *** INSTANCE;
}
-if class * implements kotlinx.serialization.internal.GeneratedSerializer {
    static ** $instance;
}
-keepclassmembers class <1> {
    static <1>$serializer INSTANCE;
}

# ===== Compose (BOM 2026.05 + Kotlin Compose Compiler Plugin) =====
# Compose runtime 内部类（2026 BOM 新增/重组）
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.material3.** { *; }
# 防止 R8 删除 @Composable 函数（Kotlin 2.x Compose compiler 生成新字节码模式）
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose compiler stability 推断相关
-keep class androidx.compose.runtime.internal.** { *; }
-keep class androidx.compose.runtime.snapshots.** { *; }

# ===== Activity 1.13.0（NavigationEventDispatcher 重写 OnBackPressedDispatcher） =====
-keep class androidx.activity.** { *; }
-keep class androidx.activity.compose.** { *; }
-keepclassmembers class androidx.activity.OnBackPressedDispatcher {
    *** addCallback(...);
    *** hasEnabledCallbacks();
    *** onBackPressed();
}

# ===== Navigation 2.9.8 (反射路由) =====
-keep class cn.a10miaomiao.bilimiao.compose.pages.**.PageConfig { *; }
-keep class * implements androidx.navigation.NavArgs { *; }
# 保留 @Serializable 导航路由对象
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
    @kotlinx.serialization.Serializable <methods>;
}

# ===== AndroidX Lifecycle =====
-keep class androidx.lifecycle.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ===== DataStore Preferences 1.2.1 =====
-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }

# ===== ProfileInstaller 1.4.1 =====
-keep class androidx.profileinstaller.** { *; }

# ===== Browser 1.10.0 =====
-keep class androidx.browser.** { *; }

# ===== 网络层 =====
-keep class okhttp3.** { *; }
-keep class com.a10miaomiao.bilimiao.comm.network.** { *; }

# ===== 播放器 =====
-keep class com.shuyu.gsyvideoplayer.** { *; }
-keepclassmembers class * extends com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer {
    <init>(...);
}
-keep class androidx.media3.** { *; }

# ===== 图片加载 (Glide) =====
-keep class com.bumptech.glide.** { *; }
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ===== DI (Kodein) =====
-keep class org.kodein.di.** { *; }
-keep class org.kodein.type.** { *; }
-keep class * extends org.kodein.type.TypeReference
-keep class * extends org.kodein.type.TypeToken

# Kodein Compose (内联函数生成类，R8 全模式需保留)
-keep class org.kodein.di.compose.** { *; }
-keepclassmembers class org.kodein.di.compose.** {
    *** $inlined$*(...);
}
-dontwarn org.kodein.di.compose.**

# ===== UI 库 =====
-keep class com.kongzue.dialogx.** { *; }
-keep class com.mikaelzero.mojito.** { *; }
-keep class splitties.** { *; }
-keep class pbandk.** { *; }

# ===== Accompanist 0.37.3 =====
-keep class com.google.accompanist.** { *; }

# ===== Compose Preference 1.1.1 =====
-keep class me.zhanghai.compose.preference.** { *; }

# ===== QRose 1.1.2 (QR code) =====
-keep class io.github.alexzhirkevich.qrose.** { *; }

# ===== Reorderable 3.1.0 (拖拽排序) =====
-keep class sh.calvin.reorderable.** { *; }

# ===== Material 1.14.0 构造器（Splitties 反射调用） =====
-keepclassmembers class com.google.android.material.** {
    <init>(android.content.Context);
    <init>(android.content.Context, android.util.AttributeSet);
}

# ===== MaterialKolor =====
-keep class com.materialkolor.** { *; }

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

# ===== WebView JS 接口（如有使用） =====
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ===== 静默警告 =====
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

# ===== Kotlinx Coroutines Flow 内部类（StateFlow 更新依赖） =====
-keep class kotlinx.coroutines.flow.StateFlowImpl { *; }
-keep class kotlinx.coroutines.flow.internal.** { *; }
# 保留 MutableStateFlow 的 value setter（Compose collectAsState 依赖）
-keepclassmembers class kotlinx.coroutines.flow.MutableStateFlow {
    *** getValue();
    *** setValue(...);
}
# 保留 data class copy/equals/hashCode/toString/componentN
-keepclassmembers class com.a10miaomiao.bilimiao.comm.store.UserStore$State {
    *** copy(...);
    *** component1();
    *** getInfo();
    *** setInfo(...);
}
-keepclassmembers class com.a10miaomiao.bilimiao.comm.entity.user.UserInfo {
    <fields>;
    *** getMid();
}
# 防止 R8 内联 BaseStore.setState 中的 copy() 调用链
-keepclassmembers class com.a10miaomiao.bilimiao.comm.store.base.BaseStore {
    *** setState(...);
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

# ===== Kodein DI 内联函数生成类（rememberInstance 等） =====
-keep class org.kodein.di.compose.**$* { *; }
-keepclassmembers class org.kodein.di.compose.** {
    *** $inlined$*(...);
}

# ===== MutableStateFlow 完整保留 =====
-keep class kotlinx.coroutines.flow.MutableStateFlow { *; }

# ===== Compose Snapshot 状态系统（collectAsState 依赖） =====
-keep class androidx.compose.runtime.snapshots.SnapshotStateObserver { *; }
-keep class androidx.compose.runtime.snapshots.SnapshotStateObserver$* { *; }
-keep class androidx.compose.runtime.snapshots.SnapshotMutableStateImpl { *; }

# ===== Kotlinx Serialization $serializer 伴生（覆盖所有 @Serializable） =====
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    static ** $serializer;
}

# ===== Http 响应实体（所有 entity 包） =====
-keep class com.a10miaomiao.bilimiao.comm.entity.** { *; }
-keepclassmembers class com.a10miaomiao.bilimiao.comm.entity.** {
    <fields>;
    <init>(...);
}
