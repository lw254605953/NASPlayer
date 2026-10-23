package com.megatron.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

data class HttpAddressValue(val https: Boolean = true, val address: String = "") {
    fun withAddress(input: String): HttpAddressValue {
        val pasted = input.trimStart()
        return when {
            pasted.startsWith("https://", ignoreCase = true) -> HttpAddressValue(true, pasted.drop(8))
            pasted.startsWith("http://", ignoreCase = true) -> HttpAddressValue(false, pasted.drop(7))
            else -> copy(address = input)
        }
    }

    fun fullUrl(): String {
        val location = address.trim()
        if (location.isEmpty()) return ""
        // Leave other explicit schemes intact so the existing URL validator rejects them.
        if (EXPLICIT_SCHEME.containsMatchIn(location)) return location
        return "${if (https) "https" else "http"}://$location"
    }

    companion object {
        private val EXPLICIT_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        fun fromUrl(value: String) = HttpAddressValue().withAddress(value)
        val Saver = listSaver<HttpAddressValue, Any>(
            save = { listOf(it.https, it.address) },
            restore = { HttpAddressValue(it[0] as Boolean, it[1] as String) },
        )
    }
}

@Composable
fun HttpAddressField(
    value: HttpAddressValue,
    onValueChange: (HttpAddressValue) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = value.https, onClick = { onValueChange(value.copy(https = true)) }, label = { Text("HTTPS") }, enabled = enabled)
            FilterChip(selected = !value.https, onClick = { onValueChange(value.copy(https = false)) }, label = { Text("HTTP") }, enabled = enabled)
        }
        OutlinedTextField(
            value = value.address,
            onValueChange = { onValueChange(value.withAddress(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            prefix = { Text(if (value.https) "https://" else "http://") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = imeAction),
            keyboardActions = keyboardActions,
        )
    }
}
