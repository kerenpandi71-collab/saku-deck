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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
                OutlinedButton(onClick = { c.refresh() }) { Text("Cek lagi", color = Deck.Ink) }
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
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { loadApps(ctx) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF090D14))) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = if (landscape) 30.dp else 18.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("SAKU DECK", color = Color(0xFF8DBFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Pilih aplikasi", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Semua aplikasi • Virtual display", color = Color(0xFF9EADBF), fontSize = 11.sp)
                }
                c.currentApp?.let { current ->
                    Text("LANJUTKAN  ${current.label}  ›", Modifier.clip(RoundedCornerShape(50))
                        .background(Color(0xFF20364F)).clickable { c.closePicker() }
                        .padding(horizontal = 16.dp, vertical = 11.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF8DBFFF), modifier = Modifier.size(30.dp))
                }
            } else if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tidak ada aplikasi yang bisa dibuka.", color = Color.White)
                }
            } else if (landscape) {
                LazyRow(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    items(list, key = { it.component }) { entry -> TvAppCard(entry, true) { c.launch(entry) } }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 18.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.component }) { entry -> TvAppCard(entry, false) { c.launch(entry) } }
                }
            }
        }
    }
}

@Composable
private fun TvAppCard(entry: AppEntry, landscape: Boolean, onClick: () -> Unit) {
    Row(Modifier.then(if (landscape) Modifier.width(150.dp).height(154.dp) else Modifier.fillMaxWidth().height(82.dp))
        .clip(RoundedCornerShape(18.dp)).background(Color(0xFF172231)).clickable(onClick = onClick)
        .padding(if (landscape) 14.dp else 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(entry)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("BUKA APLIKASI", color = Color(0xFF9CCBFF), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
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
fun AppIcon(entry: AppEntry) {
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
