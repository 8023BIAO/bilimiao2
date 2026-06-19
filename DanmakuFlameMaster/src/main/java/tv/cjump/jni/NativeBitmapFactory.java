
package tv.cjump.jni;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;

/**
 * Bitmap 工厂，原版尝试加载 ndkbitmap so（仅 API 11-22）。
 * 现代 Android（API 23+）直接使用标准 Bitmap.createBitmap，该 so 不会加载。
 */
public class NativeBitmapFactory {

    /**
     * 始终使用标准 Bitmap.createBitmap（API 23+ 不需要 native so）
     */
    public static Bitmap createBitmap(int width, int height, Bitmap.Config config) {
        return Bitmap.createBitmap(width, height, config);
    }

    public static void recycle(Bitmap bitmap) {
        bitmap.recycle();
    }

    // 兼容旧版调用——无操作
    public static void loadLibs() {
        // 不再需要加载 ndkbitmap so
    }

    public static void releaseLibs() {
        // 不再需要释放
    }

    public static boolean isInNativeAlloc() {
        return false;
    }
}
