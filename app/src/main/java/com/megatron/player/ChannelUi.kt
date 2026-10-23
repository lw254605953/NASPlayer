package com.megatron.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChannelHeading(title: String, caption: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (caption != null) Text(caption, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
fun ChannelGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .055f))) { Column(content = content) }
}

@Composable
fun ChannelLabel(title: String) {
    Text(title, Modifier.padding(top = 13.dp, bottom = 4.dp), fontSize = 13.sp,
        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun BetaBadge() {
    val ink = LocalContentColor.current
    Surface(shape = RoundedCornerShape(5.dp), color = ink.copy(alpha = .10f)) {
        Text("Beta", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = ink,
            fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun BetaFeatureLabel(title: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f, fill = false), style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        BetaBadge()
    }
}

@Composable
fun ChannelRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    beta: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
    Row(Modifier.fillMaxWidth().then(interaction).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        icon?.let { Icon(it, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (beta) BetaFeatureLabel(title, style = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            else Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChannelDivider() { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = .055f)) }
