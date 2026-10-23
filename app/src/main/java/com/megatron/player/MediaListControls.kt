package com.megatron.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun MediaListSearchDialog(query: String, placeholder: String, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(query) }
    val focus = remember { FocusRequester() }
    fun submit() { onSearch(draft.trim()); onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("搜索") }, text = {
        OutlinedTextField(draft, { draft = it.take(200) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
            placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = { if (draft.isNotEmpty()) IconButton(onClick = { draft = "" }) { Icon(Icons.Default.Close, "清空搜索文字") } })
    }, confirmButton = { TextButton(onClick = ::submit) { Text("搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
internal fun MediaListSearchFilter(query: String, onEdit: () -> Unit, onClear: () -> Unit) {
    if (query.isNotEmpty()) InputChip(selected = true, onClick = onEdit, label = { Text(query, maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { IconButton(onClick = onClear) { Icon(Icons.Default.Close, "清除搜索筛选") } })
}

@Composable
internal fun MediaListSortDialog(options: List<String>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("排序") }, text = {
        Column { options.forEach { option ->
            TextButton(onClick = { onSelect(option); onDismiss() }, Modifier.fillMaxWidth()) {
                Text(option, Modifier.weight(1f)); RadioButton(selected = option == selected, onClick = null)
            }
        } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
