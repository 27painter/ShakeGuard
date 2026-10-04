package com.shakeguard.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shakeguard.app.core.AppEntry
import com.shakeguard.app.core.Controller
import com.shakeguard.app.core.SensorState
import com.shakeguard.app.core.TrackState

@Composable
fun AppsScreen() {
    val apps by Controller.apps.collectAsState()
    val icons by Controller.icons.collectAsState()
    val loading by Controller.loading.collectAsState()
    val showSystem by Controller.showSystemApps.collectAsState()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<AppEntry?>(null) }

    val filtered = apps.filter { e ->
        (query.isBlank() || e.label.contains(query, ignoreCase = true) ||
            e.packageName.contains(query, ignoreCase = true)) &&
            when (filter) {
                1 -> !e.whitelisted && e.sensor != SensorState.DENIED
                2 -> e.sensor == SensorState.DENIED
                3 -> e.whitelisted
                else -> true
            }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索应用名或包名") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChipButton(filter == 0, "全部") { filter = 0 }
            ChipButton(filter == 1, "需处理") { filter = 1 }
            ChipButton(filter == 2, "已关闭") { filter = 2 }
            ChipButton(filter == 3, "白名单") { filter = 3 }
            ChipButton(showSystem, "显示系统应用") { Controller.setShowSystemApps(!showSystem) }
            IconButton(onClick = { Controller.refresh() }) {
                Text("刷新", style = MaterialTheme.typography.labelMedium)
            }
        }

        Text(
            if (loading) "正在读取权限状态…（共 ${filtered.size} 个）"
            else "共 ${filtered.size} 个应用　已关闭摇一摇：${Controller.shakeDeniedCount()} 个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = filtered, key = { it.packageName }) { entry ->
                AppRow(entry, icons[entry.packageName]) { detail = entry }
            }
        }
    }

    detail?.let { entry ->
        AppDetailDialog(entry) { detail = null }
    }
}

@Composable
private fun ChipButton(selected: Boolean, text: String, onClick: () -> Unit) {
    if (selected) {
        androidx.compose.material3.Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AppRow(entry: AppEntry, icon: ImageBitmap?, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
            } else {
                Box(Modifier.size(40.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.whitelisted) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "★",
                            color = WarnColor,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Text(
                    entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateChip("摇一摇 " + entry.sensor.short, sensorColor(entry.sensor))
                    StateChip("追踪 " + entry.track.short, trackColor(entry.track))
                }
            }
        }
    }
}

@Composable
private fun StateChip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun AppDetailDialog(entry: AppEntry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.label) },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                Text("摇一摇传感器：${entry.sensor.label}", style = MaterialTheme.typography.bodySmall)
                Text("广告追踪（读取应用列表）：${entry.track.label}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("单独操作这个应用", style = MaterialTheme.typography.titleSmall)
                ActionRow("关闭摇一摇（设为“不允许”）") {
                    Controller.applyOne(entry.packageName, entry.label, SensorState.OPT_DENY, null)
                    onDismiss()
                }
                ActionRow("允许摇一摇（导航 / 赛车游戏）") {
                    Controller.applyOne(entry.packageName, entry.label, SensorState.OPT_ALLOW, null)
                    onDismiss()
                }
                ActionRow("还原为系统默认（仅开屏时不允许）") {
                    Controller.applyOne(entry.packageName, entry.label, SensorState.OPT_SPLASH, null)
                    onDismiss()
                }
                ActionRow("禁止读取应用列表") {
                    Controller.applyOne(entry.packageName, entry.label, null, TrackState.OPT_DENY)
                    onDismiss()
                }
                ActionRow("允许读取应用列表") {
                    Controller.applyOne(entry.packageName, entry.label, null, TrackState.OPT_ALLOW)
                    onDismiss()
                }
                ActionRow(if (entry.whitelisted) "移出白名单（并关闭摇一摇）" else "加入白名单（并允许摇一摇）") {
                    Controller.setWhitelisted(entry.packageName, entry.label, !entry.whitelisted)
                    onDismiss()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
