package cn.a10miaomiao.bilimiao.compose.pages.user

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.a10miaomiao.bilimiao.compose.common.navigation.PageNavigation
import cn.a10miaomiao.bilimiao.compose.components.dialogs.MessageDialogState
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.BangumiDetailPage
import cn.a10miaomiao.bilimiao.compose.pages.bangumi.SeasonCheckPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyBangumiPage
import cn.a10miaomiao.bilimiao.compose.pages.mine.MyFollowPage
import cn.a10miaomiao.bilimiao.compose.pages.message.ChatPage
import cn.a10miaomiao.bilimiao.compose.pages.user.MyFollowerPage
import com.a10miaomiao.bilimiao.comm.apis.UserApi
import com.a10miaomiao.bilimiao.comm.entity.MessageInfo
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.user.SpaceInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuItemPropInfo
import com.a10miaomiao.bilimiao.comm.mypage.MenuKeys
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.FilterStore
import com.a10miaomiao.bilimiao.comm.store.UserStore
import com.a10miaomiao.bilimiao.comm.utils.BiliUrlMatcher
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

class UserSpaceViewModel(
    override val di: DI,
    val vmid: String,
    val archiveViewModel: UserArchiveViewModel,
    val articleViewModel: UserArticleViewModel,
) : ViewModel(), DIAware {

    private val pageNavigation by instance<PageNavigation>()
    private val messageDialog by instance<MessageDialogState>()
    val activity: AppCompatActivity by instance()
    val userStore: UserStore by instance()
    val filterStore: FilterStore by instance()

    private val _loading = MutableStateFlow(false);
    val loading: StateFlow<Boolean> get() = _loading

    private val _fail = MutableStateFlow<Any?>(null)
    val fail: StateFlow<Any?> get() = _fail

    private val _detailData = MutableStateFlow<SpaceInfo?>(null)
    val detailData: StateFlow<SpaceInfo?> get() = _detailData

    private val _isFollow = MutableStateFlow(false)
    val isFollow: StateFlow<Boolean> get() = _isFollow

    private val _isFiltered = mutableStateOf(!filterStore.filterUpper(vmid))
    val isFiltered get() = _isFiltered.value

    val isSelf get() = userStore.isSelf(vmid)

    /**
     * 已经提示过的「当前空间 mid → 目标 mid」组合，只服务 [hintIfStuckOnOtherSpace]：
     * 本页 VM 会被同一个 nav entry 复用，返回页面会再进一次组合，靠它避免重复弹同一句提示。
     */
    private var hintedKey: String? = null

    val tabs = listOf(
        UserSpacePageTabs.Index(this),
        UserSpacePageTabs.Dynamic(vmid),
        UserSpacePageTabs.Archive(archiveViewModel),
        UserSpacePageTabs.Article(this, articleViewModel),
    )

    val pagerState = PagerState{ tabs.size }
    val currentPage get() = pagerState.currentPage

    init {
        if (vmid.isNotBlank()) {
            loadData()
        }
    }

    suspend fun changeTab(index: Int, animate: Boolean = false) {
        if (animate) {
            pagerState.animateScrollToPage(index)
        } else {
            pagerState.scrollToPage(index)
        }
    }

    fun loadData() = viewModelScope.launch(Dispatchers.IO) {
        try {
            _loading.value = true
            _fail.value = null
            val res = BiliApiService
                .userApi
                .space(vmid)
                .awaitCall()
                .json<ResponseData<SpaceInfo>>()
            if (res.code == 0) {
                val result = res.requireData()
                _detailData.value = result
                _isFollow.value = result.card.relation.is_follow == 1
            } else {
                // 已注销账号：空间接口回 -404（官方文档写着"用户不存在（如注销账号）"）。
                // 原来直接把接口的原始 message 甩进失败框 + toast，用户看到的是机器话；
                // 这里换成一句人话，页面就会显示"该账号已注销"。
                // 只认 -404：实测 -404 = 用户不存在（已注销账号就是这条）。
                // ★别把 -400 也算进来：-400 是"请求错误"，与账号状态无关（vmid=0 / 非法 vmid 都是 -400），
                //   而文章作者按钮等路径可能拼出 space/0 ⇒ 会把正常场景误报成"已注销"。
                if (res.code == -404) {
                    _fail.value = "该账号不存在或已注销"
                } else {
                    _fail.value = res.message
                    toast(res.message)
                }
            }
        } catch (e: Exception) {
            _fail.value = e
            toast("网络错误")
            e.printStackTrace()
        } finally {
            _loading.value = false
        }
    }

    fun filterUpperDelete () {
        filterStore.deleteUpper(vmid.toLong())
        _isFiltered.value = false
    }

    fun filterUpperAdd () {
        val info = detailData.value
        if (info == null) {
            toast("请等待信息加载完成")
        } else {
            filterStore.addUpper(
                info.card.mid.toLong(),
                info.card.name,
            )
            _isFiltered.value = true
        }
    }

    fun getUserSpaceUrl (): String {
        return "https://space.bilibili.com/${vmid}"
    }

    fun attention() = viewModelScope.launch(Dispatchers.IO) {
        if (!userStore.isLogin()) {
            toast("请先登录")
            return@launch
        }
        try {
            val data = detailData.value ?: return@launch
            val mode = if (isFollow.value) { 2 } else { 1 }
            val res = BiliApiService.userRelationApi
                .modify(vmid, mode)
                .awaitCall().json<MessageInfo>()
            if (res.code == 0) {
                _isFollow.value = mode == 1
                toast(if (mode == 1) {
                    "关注成功"
                } else {
                    "已取消关注"
                })
            } else {
                toast(res.message)
            }
        } catch (e: Exception) {
            toast("网络错误")
            e.printStackTrace()
        }
    }

    /** 自己的空间 → 「编辑资料」。目标页已在 BilimiaoPageRoute 里 composable 注册（规则 C） */
    fun toEditProfile() {
        pageNavigation.navigate(EditProfilePage())
    }

    /**
     * 在**别人的空间**里从抽屉点了自己头像（目标是自己的空间）时，只给一句提示。
     *
     * ★ 为什么需要它：导航框架的 `launchSingleTop` 对"同一个路由、不同参数"只会**复用同一个
     *   nav entry**（`NavControllerImpl.launchSingleTopInternal` 用旧 entry 的 id 和 ViewModelStore
     *   建出新 entry），所以本页的 ViewModel 还停在上一个用户上 —— 页面切不过去，
     *   用户看到的就是"点了没反应、一直在瞎点"。
     *   ★ 用户 2026-10-01 明确：这里**只加提示**，不重建页面、不改导航结构
     *   （真要就地切过去得走 VideoDetailPage 那套"单个 VM + 换目标"，是另一件事）。
     *
     * 只在「目标是自己、当前却停在别人空间」时提示：
     * 自己空间点别人头像（同一条复用路径的反向）不会被误伤，正常看别人的空间也不会被打扰。
     *
     * ★ 为什么要 [hintedKey]：调用点是 `UserSpacePage.Content()` 里的 `LaunchedEffect(viewModel, id)`
     *   —— 那是"每次进组合"语义。而本页 VM 恰恰是**被复用**的那个（见上），
     *   于是"点自己头像 → 进某个详情页 → 返回"会再进一次组合、**再弹一次**同样的提示。
     *   这里按"当前空间 mid → 目标 mid"记一次：同一组合只提示一次；
     *   换成第三个空间再点自己头像（vmid 变了）仍会正常提示。
     */
    fun hintIfStuckOnOtherSpace(targetId: String) {
        if (vmid == targetId) return
        if (!userStore.isSelf(targetId)) return
        val key = "$vmid->$targetId"
        if (key == hintedKey) return
        hintedKey = key
        toast("当前是他人空间，退出后再进自己的空间")
    }

    fun toFans() {
        pageNavigation.navigate(MyFollowerPage(vmid = vmid))
    }

    fun toFollow() {
        if (isSelf) {
            pageNavigation.navigate(MyFollowPage())
        } else {
            pageNavigation.navigate(UserFollowPage(vmid))
        }
    }

    fun showLikeInfo() {
        val detailInfo = detailData.value ?: return
        messageDialog.alert(
            title = detailInfo.card.name,
            text = "${detailInfo.card.likes.skr_tip}：${detailInfo.card.likes.like_num}"
        )
    }

    fun toBangumiFollow() {
        if (isSelf) {
            pageNavigation.navigate(MyBangumiPage())
        } else {
            pageNavigation.navigate(UserBangumiPage(vmid))
        }
    }


    fun toLikeArchive() {
        pageNavigation.navigate(UserLikeArchivePage(vmid))
    }

    fun toVideoDetail(item: SpaceInfo.ArchiveItem) {
        pageNavigation.navigateToVideoInfo(item.param)
    }

    fun toBangumiDetail(item: SpaceInfo.SeasonItem) {
        pageNavigation.navigate(SeasonCheckPage(
            id = item.param
        ))
    }

    fun toFavouriteList() {
        pageNavigation.navigate(UserFavouritePage(
            mid = vmid
        ))
    }

    fun toFavouriteDetail(item: SpaceInfo.Favourite2Item) {
        pageNavigation.navigate(UserFavouriteDetailPage(
            id = item.media_id,
            title = item.title
        ))
    }

    fun menuItemClick(view: View, item: MenuItemPropInfo) {
        when (item.key) {
            // 取消屏蔽
            1 -> filterUpperDelete()
            // 屏蔽
            2 -> filterUpperAdd()
            // 用浏览器打开（已禁用）
//            3 -> {
//                val url = getUserSpaceUrl()
//                BiliUrlMatcher.toUrlLink(activity, url)
//            }
            // 复制链接
            4 -> {
                val clipboard =
                    activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val label = "url"
                val text = getUserSpaceUrl()
                val clip = ClipData.newPlainText(label, text)
                clipboard.setPrimaryClip(clip)
                toast("已复制：$text")
            }
            // 分享（已禁用）
//            5 -> {
//                val info = detailData.value
//                val url = getUserSpaceUrl()
//                val shareIntent = Intent().also {
//                    it.action = Intent.ACTION_SEND
//                    it.type = "text/plain"
//                    it.putExtra(Intent.EXTRA_SUBJECT, "这个UP主非常nice")
//                    it.putExtra(
//                        Intent.EXTRA_TEXT,
//                        info?.card?.name + " " + url
//                    )
//                }
//                activity.startActivity(Intent.createChooser(shareIntent, "分享"))
//            }
            11, 12, 13 -> {
                archiveViewModel.changeRankOrder(item.action ?: "")
            }
            MenuKeys.follow -> {
                attention()
            }
            MenuKeys.edit -> {
                toEditProfile()
            }
            MenuKeys.message -> {
                val info = detailData.value ?: return
                pageNavigation.navigate(ChatPage(
                    talkerId = vmid.toLong(),
                    talkerName = info.card.name,
                    talkerFace = info.card.face,
                ))
            }
        }
    }

    fun searchSelfPage(keyword: String) {
        pageNavigation.navigate(UserSpaceSearchPage(
            id = vmid,
            keyword = keyword,
        ))
    }

}