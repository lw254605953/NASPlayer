package com.megatron.player

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Content owns vertical gestures, including at scroll boundaries; the sheet stays fixed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        // Preserve header clearance without showing a handle that cannot be dragged.
        dragHandle = { Spacer(Modifier.height(48.dp)) },
        content = content,
    )
}
