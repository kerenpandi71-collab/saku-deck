package com.saku.deck.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.saku.deck.AppEntry
import com.saku.deck.DeckController
import com.saku.deck.RecentTask
import com.saku.deck.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Warm orange wallpaper like the reference console screen. */
fun Modifier.sakuWallpaper(): Modifier = drawBehind {
    drawRect(Deck.Paper)
    val big = size.minDimension * 0.85f
    drawCircle(
        Brush.radialGradient(
            listOf(Deck.Accent, Deck.Accent.copy(alpha = 0f)),
            center = Offset(size.width * 0.78f, size.height * 0.30f),
            radius = big,
        ),
        radius = big,
        center = Offset(size.width * 0.78f, size.height * 0.30f),
    )
    val small = size.minDimension * 0.65f
    drawCircle(
        Brush.radialGradient(
            listOf(Deck.AccentSoft, Deck.AccentSoft.copy(alpha = 0f)),
            center = Offset(size.width * 0.30f, size.height * 0.95f),
            radius = small,
        ),
        radius = small,
        center = Offset(size.width * 0.30f, size.height * 0.95f),
    )
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Deck.Accent, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().sakuWallpaper(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.82f))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
fun ShizukuPanel(c: DeckController) {
    val ctx = LocalContext.current
    CenterCard {
        Text("Saku Deck", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Deck.Ink)
        when (c.shizukuState) {
            ShizukuState.NOT_INSTALLED -> {
                Info("Shizuku belum terpasang. Pasang Shizuku dulu supaya layar tengah bisa dipakai.")
                AccentButton("Unduh Shizuku") { c.openShizuku(ctx) }
            }
            ShizukuState.NOT_RUNNING -> {
                Info("Shizuku belum berjalan. Buka Shizuku lalu tekan Start lewat Wireless debugging.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("Buka Shizuku") { c.openShizuku(ctx) }
                    OutlinedButton(onClick = { c.refresh() }) { Text("Cek lagi", color = Deck.Ink) }
                }
            }
            ShizukuState.NO_PERMISSION -> {
                Info("Saku Deck butuh izin Shizuku untuk membuat layar virtual di tengah.")
                AccentButton("Izinkan") { c.requestPermission() }
            }
            ShizukuState.CONNECTING, ShizukuState.READY -> {
                CircularProgressIndicator(color = Deck.Accent, modifier = Modifier.size(28.dp))
                Info("Menghubungkan ke Shizuku…")
            }
        }
    }
}

@Composable
fun LoadingPanel(c: DeckController) {
    CenterCard {
        if (c.message == null || c.busy) {
            CircularProgressIndicator(color = Deck.Accent, modifier = Modifier.size(28.dp))
            Info("Menyiapkan layar virtual…")
        } else {
            Info("Layar virtual belum bisa dibuat.")
            AccentButton("Coba lagi") { c.retryDisplay() }
        }
    }
}

@Composable
private fun Info(text: String) {
    Text(text, fontSize = 13.sp, color = Deck.Ink.copy(alpha = 0.8f), textAlign = TextAlign.Center)
}

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map {
            AppEntry(
                label = it.loadLabel(pm).toString(),
                packageName = it.activityInfo.packageName,
                component = "${it.activityInfo.packageName}/${it.activityInfo.name}",
            )
        }
        .distinctBy { it.component }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun AppPicker(c: DeckController) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    Column(Modifier.fillMaxSize().sakuWallpaper()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pilih aplikasi", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Deck.Ink)
            Spacer(Modifier.weight(1f))
            c.currentApp?.let { current ->
                Text(
                    "Lanjutkan ${current.label} ›",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Deck.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { c.closePicker() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Deck.Accent, modifier = Modifier.size(28.dp))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(78.dp),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.component }) { entry ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { c.launch(entry) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(entry)
                        Text(
                            entry.label,
                            fontSize = 11.sp,
                            color = Deck.Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentsPanel(c: DeckController) {
    Column(Modifier.fillMaxSize().sakuWallpaper()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Aplikasi terbaru", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Deck.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                "SEGARKAN",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Deck.Ink.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { c.refreshRecents() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "TUTUP",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Deck.Accent,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { c.toggleRecents() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        val tasks = c.recentTasks
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tidak ada aplikasi terbuka di layar virtual.",
                    color = Deck.Ink,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tasks.forEach { task -> RecentRow(c, task) }
            }
        }
    }
}

@Composable
private fun RecentRow(c: DeckController, task: RecentTask) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, task.component) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cn = ComponentName.unflattenFromString(task.component)!!
                ctx.packageManager.getActivityIcon(cn).toBitmap(128, 128).asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.72f))
            .clickable { c.switchToTask(task) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bmp, contentDescription = task.label, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Deck.BodyShade))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                task.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Deck.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                task.component,
                fontSize = 10.sp,
                color = Deck.Ink.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("BUKA", color = Deck.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MappingDialog(c: DeckController) {
    AlertDialog(
        onDismissRequest = { c.closeMappingMenu() },
        title = { Text("Mapping Mobile Legends") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Info("Pilih kontrol lalu ketuk posisinya di layar game. Skill dapat ditahan, diseret, lalu dilepas.")
                c.touchMappings.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { c.armMapping(target.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            target.badge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(Deck.Accent).padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(target.label, color = Deck.Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("${(target.x * 100).toInt()}%, ${(target.y * 100).toInt()}%", color = Deck.Ink.copy(alpha = 0.64f), fontSize = 10.sp)
                        }
                        Text("ATUR", color = Deck.Accent, fontSize = 9.sp)
                    }
                }
                OutlinedButton(onClick = { c.beginControlLayoutEdit() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Atur posisi A/B/X/Y + Spell/Regen/Recall + analog kanan")
                }
                OutlinedButton(onClick = { c.resetMobileLegendsMapping() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pulihkan preset ML")
                }
                OutlinedButton(onClick = { c.checkInputStatus() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cek status touch")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.closeMappingMenu() }) { Text("Selesai") }
        },
    )
}

@Composable
private fun AppIcon(entry: AppEntry) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, entry.component) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cn = ComponentName.unflattenFromString(entry.component)!!
                ctx.packageManager.getActivityIcon(cn).toBitmap(128, 128).asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = entry.label, modifier = Modifier.size(46.dp))
    } else {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Deck.BodyShade)
        )
    }
}

@Composable
fun MessageBanner(text: String, modifier: Modifier, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(7000)
        onDismiss()
    }
    Box(
        modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable { onDismiss() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}
