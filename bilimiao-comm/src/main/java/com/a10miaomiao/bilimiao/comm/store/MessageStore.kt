package com.a10miaomiao.bilimiao.comm.store

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a10miaomiao.bilimiao.comm.BilimiaoCommApp
import com.a10miaomiao.bilimiao.comm.entity.ResponseData
import com.a10miaomiao.bilimiao.comm.entity.ResultInfo
import com.a10miaomiao.bilimiao.comm.entity.message.UnreadMessageInfo
import com.a10miaomiao.bilimiao.comm.entity.user.UserInfo
import com.a10miaomiao.bilimiao.comm.network.BiliApiService
import com.a10miaomiao.bilimiao.comm.network.MiaoHttp.Companion.json
import com.a10miaomiao.bilimiao.comm.store.base.BaseStore
import com.a10miaomiao.bilimiao.comm.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import java.io.File

class MessageStore(override val di: DI) :
    ViewModel(), BaseStore<MessageStore.State> {

    data class State (
        var unread: UnreadMessageInfo? = null
    ) {
        fun totalCount(): Int {
            return unread?.let { unread ->
                unread.reply + unread.at + unread.like + unread.chat
            } ?: 0
        }
    }

    override val stateFlow = MutableStateFlow(State())
    override fun copyState() = state.copy()

    private val activity: AppCompatActivity by instance()

    override fun init(context: Context) {
        super.init(context)
    }

    fun getUnreadMessage() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val res = BiliApiService.messageApi
                .unread()
                .awaitCall()
                .json<ResponseData<UnreadMessageInfo>>()
            if (res.isSuccess) {
                setState {
                    unread = res.data
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearUnread() {
        setState {
            unread = null
        }
    }

    fun clearReplyUnread() {
        setState {
            unread = unread?.copy(
                reply = 0
            )
        }
    }

    /** 读完私信后清私信未读：原来只有 reply/like/at 三个，私信红点永远消不掉 */
    fun clearChatUnread() {
        setState {
            unread = unread?.copy(
                chat = 0
            )
        }
    }

    /**
     * 读完系统通知后清系统通知未读。
     *
     * 未读数来自 x/msgfeed/unread 的 sys_msg 字段（消息页 Tab 上那个红点用的就是它）。
     * 消红点除了改本地状态，还要把服务端游标推上去（见 MessageAPI.sysUpdateCursor），
     * 否则下次拉 unread 又会把它带回来。
     */
    fun clearSysMsgUnread() {
        setState {
            unread = unread?.copy(
                sys_msg = 0
            )
        }
    }

    fun clearLikeUnread() {
        setState {
            unread = unread?.copy(
                like = 0
            )
        }
    }

    fun clearAtUnread() {
        setState {
            unread = unread?.copy(
                at = 0
            )
        }
    }
}