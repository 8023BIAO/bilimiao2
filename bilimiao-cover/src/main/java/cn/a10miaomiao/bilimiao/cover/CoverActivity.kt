package cn.a10miaomiao.bilimiao.cover

import android.content.*
import android.graphics.Bitmap
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import kotlinx.coroutines.runBlocking
import net.mikaelzero.mojito.ext.mojito
import java.io.File
import java.io.FileOutputStream


class CoverActivity : AppCompatActivity() {

    private val path by lazy {
        File(getExternalFilesDir(null), "BiliMiao/封面").also {
            if (!it.exists()) it.mkdirs()
        }.absolutePath + "/"
    }

    companion object {
        fun launch(context: Context, id: String, type: String) {
            val mIntent = Intent(context, CoverActivity::class.java)
            mIntent.putExtra("id", id)
            mIntent.putExtra("type", type)
            context.startActivity(mIntent)
        }

        fun launch(context: Context, id: String) {
            if (id.startsWith("BV")) {
                launch(context, id, "BV")
            } else {
                launch(context, id, "AV")
            }
        }
    }

    private lateinit var viewModel: CoverViewModel

    private val mMotionLayout by lazy { findViewById<MotionLayout>(R.id.mMotionLayout) }
    private val mMainContainerLl by lazy { findViewById<LinearLayout>(R.id.mMainContainerLl) }
    private val mBackground by lazy { findViewById<View>(R.id.mBackground) }
    private val mSaveCoverLl by lazy { findViewById<LinearLayout>(R.id.mSaveCoverLl) }
    private val mTitleTv by lazy { findViewById<TextView>(R.id.mTitleTv) }
    private val mIDTv by lazy { findViewById<TextView>(R.id.mIDTv) }
    private val mPermissionTv by lazy { findViewById<TextView>(R.id.mPermissionTv) }
    private val mProgress by lazy { findViewById<ProgressBar>(R.id.mProgress) }
    private val mCoverIv by lazy { findViewById<ImageView>(R.id.mCoverIv) }
    private val mMoreIv by lazy { findViewById<ImageView>(R.id.mMoreIv) }
    private val mBtnBox1 by lazy { findViewById<FrameLayout>(R.id.mBtnBox1) }
    private val mBtnBox2 by lazy { findViewById<FrameLayout>(R.id.mBtnBox2) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover)
        initViewModel()
        initArgument()
        initView()
    }

    private fun initArgument() {
        intent.extras?.let { extras ->
            viewModel.setConfig("", "少女正在祈祷")
            if (extras.containsKey(Intent.EXTRA_TEXT)) {
                val text = extras.getString(Intent.EXTRA_TEXT)!!
                val urlInfo = BiliUrlMatcher.findIDByUrl(text)
                val type = urlInfo[0].uppercase()
                val id = urlInfo[1]
                if (type == "未知类型") {
                    val textList = text.split(" ")
                    if (textList.size > 1) {
                        val url = textList[textList.size - 1]
                        if (url.indexOf("https://") == 0
                            || url.indexOf("http://") == 0) {
                            viewModel.resolveUrl(url)
                        }
                    }
                } else {
                    viewModel.setConfig(type, id)
                }
                Unit
            } else if (extras.containsKey("id") && extras.containsKey("type")) {
                val type = extras.getString("type")!!.uppercase()
                val id = extras.getString("id")!!
                viewModel.setConfig(type, id)
            } else {
                viewModel.setConfig("", "未知")
            }
        }
    }

    private fun initView() {
        mMotionLayout.transitionToEnd()
        mMotionLayout.setTransitionListener(object : MotionLayout.TransitionListener {
            override fun onTransitionStarted(p0: MotionLayout?, p1: Int, p2: Int) {
            }

            override fun onTransitionChange(motionLayout: MotionLayout, i: Int, i1: Int, v: Float) {

            }

            override fun onTransitionCompleted(motionLayout: MotionLayout, p1: Int) {
                if (motionLayout.progress == 0f) {
                    finish()
                }
            }

            override fun onTransitionTrigger(motionLayout: MotionLayout, p1: Int, p2: Boolean, p3: Float) {
            }
        })
        // 设置圆角
        val roundCorner = dip(36)
        mMainContainerLl.clipToOutline = true // 开启裁剪
        mMainContainerLl.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height + roundCorner,
                    roundCorner.toFloat())
            }
        }
        ViewStyle.roundRect(dip(24))(mBtnBox1)
        ViewStyle.roundRect(dip(24))(mBtnBox2)
        ViewStyle.roundRect(dip(10))(mCoverIv)
//        mColseIv.setOnClickListener {
//            mMotionLayout.transitionToStart()
//        }
        mCoverIv.setOnClickListener {
            viewModel.coverUrl.value?.let { url ->
                mCoverIv.mojito(url)
            }
        }
        mMoreIv.setOnClickListener {
            val popupMenu = PopupMenu(this, it)
            popupMenu.inflate(R.menu.cover)
            popupMenu.setOnMenuItemClickListener(this::onMenuItemClick)
            popupMenu.show()
        }
        mSaveCoverLl.setOnClickListener {
            val bitmap = viewModel.coverBitmap.value
            if (bitmap == null) {
                toast("图片未加载")
            } else {
                saveImage(bitmap)
            }
        }
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CoverViewModel(this@CoverActivity) as T
                }
            },
        )[CoverViewModel::class.java]
        viewModel.coverBitmap.observe(this, Observer {
            if (it == null) {
                mProgress.visibility = View.VISIBLE
            } else {
                mProgress.visibility = View.GONE
                mCoverIv.setImageBitmap(it)
            }
        })
        viewModel.title.observe(this, Observer {
            it?.let { text -> mTitleTv.text = text }
        })
        viewModel.fileName.observe(this) {
            mIDTv.text = it
            mPermissionTv.text = "文件名:$it.jpg"
        }
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.cover_custom -> {
                toast("施工中")
            }
            R.id.cover_copy -> {
                viewModel.coverUrl.value?.let {
                    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("imageUrl", it)
                    clipboardManager.setPrimaryClip(clipData)
                    toast("图片链接已复制到剪切板")
                }
            }
            R.id.cover_more -> {
                viewModel.openMore()
            }
        }
        return true
    }


    /**
     * 保存图片(MediaStore优先,失败回退私有目录)
     */
    private fun saveImage(bitmap: Bitmap) {
        val fileName = "${viewModel.fileName.value ?: "未命名"}.jpg"
        try {
            saveToAlbum(fileName, bitmap)
            toast("封面已保存至相册")
        } catch (e: Exception) {
            try {
                File(path).let {
                    if (!it.exists()) it.mkdirs()
                }
                val outFile = File(path + fileName)
                outFile.writeBitmap(bitmap)
                toast("封面已保存至本地")
            } catch (e2: Exception) {
                e2.printStackTrace()
                toast("保存失败")
            }
        }
    }

    private fun saveToAlbum(fileName: String, bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Bilimiao")
        }
        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.flush()
            }
        } ?: throw Exception("MediaStore insert failed")
    }

    /**
     * 保存图片
     */
    private fun File.writeBitmap(data: Bitmap) {
        val fOut = FileOutputStream(this)
        data.compress(Bitmap.CompressFormat.JPEG, 100, fOut)
        fOut.flush()
        fOut.close()
    }

    /**
     * 将文件保存到公共的媒体文件夹
     */
    fun saveSignImage(fileName: String, bitmap: Bitmap) {
        try {
            //设置保存参数到ContentValues中
            val contentValues = ContentValues()
            //设置文件名
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)

            //android Q中不再使用DATA字段,而用RELATIVE_PATH代替
            //RELATIVE_PATH是相对路径不是绝对路径
            //DCIM是系统文件夹,关于系统文件夹可以到系统自带的文件管理器中查看,不可以写没存在的名字
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/BilimiaoCover")
            //contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Music/signImage");
            //设置文件类型
            contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/JPEG")
            //执行insert操作,向系统文件夹中添加文件
            //EXTERNAL_CONTENT_URI代表外部存储器,该值不变
            val uri =
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                //若生成了uri,则表示该文件添加成功
                //使用流将内容写入该uri中即可
                val outputStream = contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    outputStream.flush()
                    outputStream.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getThemeMode(): Int {
        return runBlocking {
            SettingPreferences.mapData(this@CoverActivity) { prefs ->
                prefs[SettingPreferences.ThemeDarkMode] ?: 0
            }
        }
    }

    private fun toast(msg: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { toast(msg) }
            return
        }
        // 完全自定义的提示浮层,不依赖 Toast / Snackbar / Dialog(国产ROM会拦截/改主题)
        val isDark = when (getThemeMode()) {
            1 -> false
            2 -> true
            else -> {
                val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        val bgColor = if (isDark) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

        val tip = TextView(this).apply {
            text = msg
            setTextColor(textColor)
            textSize = 14f
            setPadding(dip(24), dip(12), dip(24), dip(12))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dip(12).toFloat()
            }
            background = bg
            elevation = dip(8).toFloat()
            gravity = Gravity.CENTER
        }

        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = dip(140)
        }
        root.addView(tip, params)

        // 渐入动画
        tip.alpha = 0f
        tip.animate().alpha(1f).setDuration(200).start()

        // 2 秒后自动移除
        Handler(Looper.getMainLooper()).postDelayed({
            tip.animate().alpha(0f).setDuration(300).withEndAction {
                root.removeView(tip)
            }.start()
        }, 2000)
    }

    private fun dip(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onBackPressed() {
        mMotionLayout.transitionToStart()
        super.onBackPressed()
    }
}