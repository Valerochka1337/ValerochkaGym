package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus

/** Бейдж статуса выгрузки: иконка облака — нейтральное «Ожидает», primary «Выгружено», error «Ошибка». */
@Composable
fun UploadStatusBadge(status: UploadStatus) {
    val icon: ImageVector
    val color: Color
    val description: String
    when (status) {
        UploadStatus.PENDING -> {
            icon = Icons.Rounded.CloudQueue
            color = MaterialTheme.colorScheme.onSurfaceVariant
            description = "Ожидает выгрузки"
        }
        UploadStatus.UPLOADED -> {
            icon = Icons.Rounded.CloudDone
            color = MaterialTheme.colorScheme.primary
            description = "Выгружено"
        }
        UploadStatus.FAILED -> {
            icon = Icons.Rounded.CloudOff
            color = MaterialTheme.colorScheme.error
            description = "Ошибка выгрузки"
        }
    }
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(5.dp)
            .size(18.dp),
    )
}
