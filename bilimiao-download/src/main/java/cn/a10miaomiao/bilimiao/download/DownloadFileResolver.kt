package cn.a10miaomiao.bilimiao.download

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * 下载文件定位器：下载相关的读写全部从这里走。
 *
 * 为什么要它：接入公共目录（MediaStore）之后，同一条下载记录的 `entryDirPath` 有两种身份 ——
 *  - **私有绝对路径**（`/storage/emulated/0/Android/data/<包名>/files/BiliMiao/xxx/1-1`）：
 *    下载中、发布失败、老系统用户拒绝存储权限的（文件在应用私有目录，卸载会丢）；
 *  - **公共相对路径**（`xxx/1-1`，相对 `Download/BiliMiao/`）：整目录发布成功、私有副本已删掉的。
 *
 * 以前调用方直接 `File(entryDirPath, "xxx")` 拼路径：绝对路径能用，相对路径会变成"相对进程当前目录"的
 * 野路径，必然读不到文件（播放器直接黑屏）。所以统一收口：
 *  - **绝对 → 当普通 File 用**（私有目录或更老版本的绝对路径数据）；
 *  - **相对 → 先看私有工作目录，再看 [PublicDownloadStore]**（私有优先：私有目录里有文件说明还没发布完，
 *    或者发布成功后删私有失败，两边都有时私有那份是刚写完的）。
 *
 * 所有方法都**不抛异常**（失败返回 null / 空列表 / false）：下载读取链路宁可不播，也不能崩。
 */
object DownloadFileResolver {

    /**
     * 身份判定：不以 "/" 开头就是"公共相对路径"。
     * 注意不要用 `File(path).isAbsolute`（Windows 风格/异常输入会有坑），直接看首字符最稳。
     */
    fun isPublished(entryDirPath: String): Boolean =
        entryDirPath.isNotEmpty() && !entryDirPath.startsWith("/")

    /** 私有工作目录：`<外部私有目录>/BiliMiao`（**不创建目录**，纯路径计算，UI 线程也能安全调） */
    fun workDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "BiliMiao")

    /**
     * 把"身份 + 相对文件名"还原成私有目录里的真实路径。
     *  - 绝对身份 → 原样；
     *  - 相对身份 → 映射到私有工作目录下（发布前的下载目录就是它）。
     * `name` 允许带子目录（例如 `"12345/index.json"`）。
     */
    fun privateFile(context: Context, entryDirPath: String, name: String = ""): File {
        val base = if (isPublished(entryDirPath)) File(workDir(context), entryDirPath) else File(entryDirPath)
        return if (name.isEmpty()) base else File(base, name)
    }

    /** 该文件能不能读到（私有或公共任一边存在即可） */
    fun exists(context: Context, entryDirPath: String, name: String): Boolean =
        uri(context, entryDirPath, name) != null

    /**
     * 取文件 Uri：私有 → `file://`（media3 支持）；公共 → [PublicDownloadStore.findUri] 给的 content://
     * （Android 10+ 上 MediaStore 的 content:// 不需要任何权限；9 及以下那边退回 file://）。
     */
    fun uri(context: Context, entryDirPath: String, name: String): Uri? {
        if (entryDirPath.isEmpty() || name.isEmpty()) return null
        if (!isPublished(entryDirPath)) {
            // 绝对路径身份：老数据/私有目录，直接读本地文件
            val file = privateFile(context, entryDirPath, name)
            return if (file.isFile) Uri.fromFile(file) else null
        }
        // 相对身份：私有残留优先（发布后删私有失败、或发布中途失败）
        privateFile(context, entryDirPath, name).takeIf { it.isFile }?.let { return Uri.fromFile(it) }
        val full = childPath(entryDirPath, name)
        return PublicDownloadStore.findUri(context, dirNameOf(full), nameOf(full))
    }

    fun openInput(context: Context, entryDirPath: String, name: String): InputStream? {
        val fileUri = uri(context, entryDirPath, name) ?: return null
        return try {
            context.contentResolver.openInputStream(fileUri)
        } catch (e: Exception) {
            null
        }
    }

    fun readText(context: Context, entryDirPath: String, name: String): String? {
        return try {
            openInput(context, entryDirPath, name)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 列目录下的文件名（`subDir` 是相对 [entryDirPath] 的子目录，例如视频清晰度目录 `type_tag`）。
     * 相对身份时把私有、公共两边并起来去重：发布中途失败会出现"一半在公共、一半还在私有"的状态。
     */
    fun listNames(context: Context, entryDirPath: String, subDir: String? = null): List<String> {
        if (entryDirPath.isEmpty()) return emptyList()
        val path = if (subDir.isNullOrEmpty()) entryDirPath else childPath(entryDirPath, subDir)
        val privateNames = privateFile(context, path).listFiles()?.map { it.name } ?: emptyList()
        // 绝对身份只可能是私有（或老数据的绝对路径），不用去查公共目录（MediaStore 全表查询不便宜）
        if (!isPublished(path)) return privateNames
        return (privateNames + PublicDownloadStore.listNames(context, path)).distinct()
    }

    /**
     * 删一集（或它下面的子目录）：私有、公共两边都删。
     * 为什么要两边都删：发布成功但删私有失败、发布到一半失败，都会让同一集在两边各留一份，
     * 用户点"删除"的语义是"这一集我不要了"，两边都清掉才符合预期。返回删掉的条目数（尽力而为）。
     */
    fun deleteDir(context: Context, entryDirPath: String, subDir: String? = null): Int {
        if (entryDirPath.isEmpty()) return 0
        val path = if (subDir.isNullOrEmpty()) entryDirPath else childPath(entryDirPath, subDir)
        var count = 0
        val privateDir = privateFile(context, path)
        // 兜底：绝不能把整个工作目录当"一集"删掉（目录名异常的老数据会出现 path == 工作目录）；
        // 判断不出来时按"是工作目录"处理，宁可不删也不误删用户所有下载
        val isWorkDirItself = runCatching {
            privateDir.canonicalFile == workDir(context).canonicalFile
        }.getOrDefault(true)
        if (!isWorkDirItself && privateDir.exists() && privateDir.deleteRecursively()) count++
        relativeDirOf(context, path)?.let { rel ->
            count += PublicDownloadStore.deleteDir(context, rel)
        }
        return count
    }

    /**
     * 私有绝对目录 → 公共目录用的相对路径（`"xxx/1-1"`）。
     *  - 已经是相对身份 → 原样返回；
     *  - 不在工作目录内（或就是工作目录本身）→ 返回 null，调用方必须放弃发布（安全优先）。
     */
    fun relativeDirOf(context: Context, dirPath: String): String? {
        if (dirPath.isEmpty()) return null
        if (isPublished(dirPath)) return dirPath.replace('\\', '/').trim('/').takeIf { it.isNotEmpty() }
        return try {
            val work = workDir(context).canonicalFile
            val dir = File(dirPath).canonicalFile
            val rel = dir.toRelativeString(work).replace('\\', '/').trim('/')
            // toRelativeString 在不同根/无法相对化时会原样返回绝对路径，必须排除；
            // "../" 开头说明在私有工作目录之外 —— 都不能拿去当公共目录的子路径
            if (rel.isEmpty() || rel.startsWith("/") || rel.startsWith("..")) null else rel
        } catch (e: Exception) {
            null
        }
    }

    /** 拼路径（保留绝对/相对语义，不解析 `..`，仅内部拼接用） */
    fun childPath(dirPath: String, name: String): String = when {
        dirPath.isEmpty() -> name
        dirPath.endsWith("/") -> "$dirPath$name"
        else -> "$dirPath/$name"
    }

    /** 取路径最后一段（文件名 / 目录名） */
    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** 取路径倒数第二段（父目录名）；没有父目录时返回 "" */
    fun dirNameOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")
}
