package cn.a10miaomiao.bilimiao.compose.pages.filter.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.a10miaomiao.bilimiao.comm.datastore.SettingPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun FilterCommentWordListContent() {
    val context = LocalContext.current
    val dataStore = remember {
        SettingPreferences.run { context.dataStore }
    }
    val scope = rememberCoroutineScope()
    val allWords by dataStore.data.map { prefs ->
        prefs[SettingPreferences.CommentBlockedWords] ?: emptySet()
    }.collectAsState(initial = emptySet())

    val selectedMap = remember {
        mutableStateMapOf<String, Int>()
    }

    var inputMode by remember {
        mutableStateOf(-2)
    }
    var inputText by remember {
        mutableStateOf("")
    }
    var errorText by remember {
        mutableStateOf("")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (allWords.isNotEmpty()
                    && selectedMap.size == allWords.size) {
                    TextButton(onClick = {
                        selectedMap.clear()
                    }) {
                        Text(text = "取消全选")
                    }
                } else {
                    TextButton(
                        onClick = {
                            allWords.forEachIndexed { index, s ->
                                selectedMap[s] = index
                            }
                        },
                        enabled = allWords.isNotEmpty(),
                    ) {
                        Text(text = "全选")
                    }
                }

                TextButton(
                    onClick = {
                        val toRemove = selectedMap.keys.toList()
                        if (toRemove.isEmpty()) return@TextButton
                        scope.launch {
                            dataStore.edit { prefs ->
                                val current = prefs[SettingPreferences.CommentBlockedWords]
                                    ?: emptySet()
                                prefs[SettingPreferences.CommentBlockedWords] =
                                    current - toRemove.toSet()
                            }
                        }
                        selectedMap.clear()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    enabled = selectedMap.isNotEmpty()
                ) {
                    Text(text = "删除选中")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = {
                    inputMode = -1
                    inputText = ""
                }) {
                    Text(text = "添加")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                items(allWords.toList()) { word ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = selectedMap.contains(word),
                            onCheckedChange = {
                                if (selectedMap.contains(word)) {
                                    selectedMap.remove(word)
                                } else {
                                    selectedMap[word] = allWords.indexOf(word)
                                }
                            }
                        )
                        Text(
                            text = word,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (allWords.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .height(400.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无屏蔽词\n添加后评论区将过滤含这些关键词的内容",
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(inputMode) {
        if (inputMode > -2) {
            launch {
                focusRequester.requestFocus()
            }
        }
    }

    fun handleDismiss() {
        inputMode = -2
        inputText = ""
        errorText = ""
    }

    fun handleConfirm() {
        if (inputText.isBlank()) {
            errorText = "请输入关键词"
            return
        }
        val textToAdd = inputText
        scope.launch {
            dataStore.edit { prefs ->
                val current = prefs[SettingPreferences.CommentBlockedWords] ?: emptySet()
                prefs[SettingPreferences.CommentBlockedWords] = current + textToAdd
            }
            handleDismiss()
        }
    }

    if (inputMode > -2) {
        AlertDialog(
            onDismissRequest = ::handleDismiss,
            title = {
                if (inputMode < 0) {
                    Text(text = "添加评论屏蔽关键词")
                } else {
                    Text(text = "编辑评论屏蔽关键词")
                }
            },
            text = {
                Column {
                    TextField(
                        label = { Text(text = "关键词") },
                        isError = errorText.isNotBlank(),
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            errorText = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { handleConfirm() }
                        ),
                    )
                    Text(text = "注：支持正则表达式（语法：/正则表达式主体/）")
                }
            },
            confirmButton = {
                TextButton(onClick = ::handleConfirm) {
                    Text("确认添加")
                }
            },
            dismissButton = {
                TextButton(onClick = ::handleDismiss) {
                    Text("取消")
                }
            }
        )
    }
}
