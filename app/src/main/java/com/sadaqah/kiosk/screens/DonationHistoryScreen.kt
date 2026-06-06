package com.sadaqah.kiosk.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sadaqah.kiosk.R
import com.sadaqah.kiosk.donations.DonationCsvExporter
import com.sadaqah.kiosk.donations.DonationHistory
import com.sadaqah.kiosk.donations.DonationStats
import com.sadaqah.kiosk.donations.DonationStats.Timeframe
import com.sadaqah.kiosk.model.Settings
import com.sadaqah.kiosk.rememberStrings
import com.sadaqah.kiosk.responsiveDp
import com.sadaqah.kiosk.responsiveSp
import com.sadaqah.kiosk.screens.getCurrencySymbol
import java.math.BigDecimal
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DonationHistoryScreen(
    settings: Settings,
    history: DonationHistory,
    onSettingsChange: (Settings) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)
    val button = Color(settings.buttonColor)
    val currencySymbol = getCurrencySymbol(settings.currency)

    var timeframe by remember { mutableStateOf(Timeframe.LAST_7_DAYS) }
    var refreshKey by remember { mutableStateOf(0) }

    // Re-read when the timeframe changes or after Clear. Non-All-time windows
    // only touch the relevant year shards, so very old kiosks (5+ years of
    // history) don't pay the cost of parsing ancient shards for "last 7 days".
    val entries by remember(timeframe, refreshKey) {
        mutableStateOf(
            if (timeframe == Timeframe.ALL_TIME) history.readAll()
            else history.readSince(System.currentTimeMillis() - DonationStats.windowMillis(timeframe))
        )
    }
    var tab by remember { mutableStateOf(0) } // 0 = Overview, 1 = Frequency
    var showResetConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val summary = remember(entries, timeframe, settings.donationStatsStartedAtMs) {
        DonationStats.summarise(entries, timeframe, settings.donationStatsStartedAtMs)
    }
    val frequency = remember(entries, timeframe, settings.donationStatsStartedAtMs) {
        DonationStats.frequencyTable(entries, timeframe, settings.donationStatsStartedAtMs)
    }
    val bars = remember(entries, timeframe) {
        DonationStats.bars(entries, timeframe)
    }
    // Recent-donations table is filtered by the same window as the summary
    // cards / bars / frequency tab so the operator sees only what matches
    // the selected timeframe. Newest first.
    val tableEntries = remember(entries, timeframe, settings.donationStatsStartedAtMs) {
        val windowMs = DonationStats.windowMillis(timeframe)
        val nowMs = System.currentTimeMillis()
        val windowStart = if (timeframe == Timeframe.ALL_TIME) 0L else nowMs - windowMs
        val floor = if (settings.donationStatsStartedAtMs > 0)
            maxOf(windowStart, settings.donationStatsStartedAtMs) else windowStart
        entries.asSequence()
            .filter { it.timestampMs in floor..nowMs }
            .toList()
            .asReversed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        Image(
            painter = painterResource(id = R.drawable.pattern),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(Color(settings.patternColor), blendMode = BlendMode.Modulate)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(responsiveDp(24.dp))
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.donationHistory,
                    color = border,
                    fontSize = responsiveSp(40.0),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = button),
                    shape = RoundedCornerShape(responsiveDp(10.dp)),
                    border = BorderStroke(responsiveDp(2.dp), border)
                ) {
                    Text(strings.back, color = border, fontSize = responsiveSp(14.0))
                }
            }

            Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

            // Tracking toggle + measuring-since line
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strings.donationTrackingEnabled, color = border, fontSize = responsiveSp(14.0), fontWeight = FontWeight.Bold)
                    Text(
                        text = strings.donationTrackingDesc,
                        color = border.copy(alpha = 0.6f),
                        fontSize = responsiveSp(11.0),
                        lineHeight = responsiveSp(13.0)
                    )
                }
                Switch(
                    checked = settings.donationTrackingEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(donationTrackingEnabled = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = button,
                        checkedTrackColor = border,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
            if (settings.donationStatsStartedAtMs > 0) {
                Text(
                    text = "${strings.measuringSince} ${shortDate(settings.donationStatsStartedAtMs)}",
                    color = border.copy(alpha = 0.7f),
                    fontSize = responsiveSp(11.0),
                    modifier = Modifier.padding(top = responsiveDp(2.dp))
                )
            }

            Spacer(modifier = Modifier.height(responsiveDp(16.dp)))

            // ── Timeframe chips ────────────────────────────────────────────
            TimeframeChips(timeframe, settings, onSelect = { timeframe = it })

            Spacer(modifier = Modifier.height(responsiveDp(8.dp)))

            // ── Tab bar ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))
            ) {
                TabButton(strings.tabOverview, selected = tab == 0, settings = settings, onClick = { tab = 0 }, modifier = Modifier.weight(1f))
                TabButton(strings.tabFrequency, selected = tab == 1, settings = settings, onClick = { tab = 1 }, modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

            // ── Tab content ─────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    0 -> OverviewTab(
                        summary = summary,
                        entries = tableEntries,
                        bars = bars,
                        timeframe = timeframe,
                        settings = settings,
                        currencySymbol = currencySymbol
                    )
                    else -> FrequencyTab(rows = frequency, settings = settings, currencySymbol = currencySymbol)
                }
            }

            Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

            // ── Action row ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))
            ) {
                Button(
                    onClick = {
                        // Always export the full history regardless of the
                        // displayed timeframe — this is for backups, not the
                        // narrowed view on screen.
                        val path = DonationCsvExporter.export(context, history.readAll(), settings.currency)
                        if (path != null) {
                            Toast.makeText(context, "${strings.exportCsvSaved}: $path", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, strings.exportCsvFailed, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = history.hasAnyEntries(),
                    colors = ButtonDefaults.buttonColors(containerColor = button, disabledContainerColor = Color.Gray),
                    shape = RoundedCornerShape(responsiveDp(10.dp)),
                    border = BorderStroke(responsiveDp(2.dp), border),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.exportCsv, color = border, fontSize = responsiveSp(13.0))
                }
                Button(
                    onClick = { showResetConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                    shape = RoundedCornerShape(responsiveDp(10.dp)),
                    border = BorderStroke(responsiveDp(2.dp), Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.resetAverages, color = Color.White, fontSize = responsiveSp(13.0), fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(responsiveDp(10.dp)),
                    border = BorderStroke(responsiveDp(2.dp), Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.clearHistory, color = Color.White, fontSize = responsiveSp(13.0), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showResetConfirm) {
        ConfirmDialog(
            title = strings.resetAverages,
            body = strings.resetAveragesConfirm,
            confirmText = strings.resetAverages,
            settings = settings,
            onConfirm = {
                onSettingsChange(settings.copy(donationStatsStartedAtMs = System.currentTimeMillis()))
                showResetConfirm = false
            },
            onDismiss = { showResetConfirm = false }
        )
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = strings.clearHistory,
            body = strings.clearHistoryConfirm,
            confirmText = strings.clearHistory,
            destructive = true,
            settings = settings,
            onConfirm = {
                onClearHistory()
                refreshKey++
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }
}

// ── Subcomponents ────────────────────────────────────────────────────────────

@Composable
private fun TimeframeChips(current: Timeframe, settings: Settings, onSelect: (Timeframe) -> Unit) {
    val strings = rememberStrings()
    val options = listOf(
        Timeframe.TODAY to strings.tfToday,
        Timeframe.LAST_7_DAYS to strings.tf7Days,
        Timeframe.LAST_30_DAYS to strings.tf30Days,
        Timeframe.LAST_YEAR to strings.tfYear,
        Timeframe.ALL_TIME to strings.tfAllTime
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(6.dp)),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (tf, label) ->
            val selected = current == tf
            val color = if (selected) Color(settings.buttonColor) else Color.Gray
            Button(
                onClick = { onSelect(tf) },
                colors = ButtonDefaults.buttonColors(containerColor = color),
                shape = RoundedCornerShape(responsiveDp(8.dp)),
                border = BorderStroke(responsiveDp(2.dp), Color(settings.buttonBorderColor)),
                modifier = Modifier.weight(1f)
            ) {
                Text(label, color = Color(settings.buttonBorderColor), fontSize = responsiveSp(12.0))
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, settings: Settings, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(settings.buttonColor) else Color.Transparent
        ),
        shape = RoundedCornerShape(responsiveDp(8.dp)),
        border = BorderStroke(responsiveDp(2.dp), Color(settings.buttonBorderColor)),
        modifier = modifier
    ) {
        Text(
            label,
            color = Color(settings.buttonBorderColor),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = responsiveSp(14.0)
        )
    }
}

@Composable
private fun OverviewTab(
    summary: DonationStats.WindowSummary,
    entries: List<DonationHistory.Entry>,
    bars: List<DonationStats.Bar>,
    timeframe: Timeframe,
    settings: Settings,
    currencySymbol: String
) {
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary cards row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))
        ) {
            SummaryCard(strings.totalLabel, "$currencySymbol${summary.total.toPlainString()}", settings = settings, modifier = Modifier.weight(1f))
            SummaryCard(strings.countLabel, summary.count.toString(), settings = settings, modifier = Modifier.weight(1f))
            SummaryCard(strings.averagePerDonation, "$currencySymbol${summary.averagePerDonation.toPlainString()}", settings = settings, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(responsiveDp(8.dp)))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp))
        ) {
            SummaryCard(strings.averagePerDay, "$currencySymbol${summary.averagePerDay.toPlainString()}", settings = settings, modifier = Modifier.weight(1f))
            SummaryCard(strings.averagePerWeek, "$currencySymbol${summary.averagePerWeek.toPlainString()}", settings = settings, modifier = Modifier.weight(1f))
            SummaryCard(strings.averagePerMonth, "$currencySymbol${summary.averagePerMonth.toPlainString()}", settings = settings, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

        // Bar chart
        BarChart(bars = bars, timeframe = timeframe, settings = settings)

        Spacer(modifier = Modifier.height(responsiveDp(12.dp)))

        // Recent donations table
        Text(strings.recentDonations, color = border, fontSize = responsiveSp(14.0), fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(responsiveDp(4.dp)))
        if (entries.isEmpty()) {
            Text(strings.noDonationsYet, color = border.copy(alpha = 0.6f), fontSize = responsiveSp(12.0))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(BorderStroke(responsiveDp(1.dp), border.copy(alpha = 0.4f)), RoundedCornerShape(responsiveDp(8.dp)))
                    .padding(responsiveDp(8.dp))
            ) {
                items(entries) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = responsiveDp(3.dp)),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(longDate(entry.timestampMs), color = border, fontSize = responsiveSp(12.0))
                        Text("$currencySymbol${entry.amount.toPlainString()}", color = border, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencyTab(rows: List<DonationStats.FrequencyRow>, settings: Settings, currencySymbol: String) {
    val strings = rememberStrings()
    val border = Color(settings.buttonBorderColor)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = responsiveDp(6.dp))) {
            Text(strings.frequencyAmountHeader, color = border, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(strings.frequencyCountHeader, color = border, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(strings.frequencyTotalHeader, color = border, fontSize = responsiveSp(12.0), fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        if (rows.isEmpty()) {
            Text(strings.noDonationsYet, color = border.copy(alpha = 0.6f), fontSize = responsiveSp(12.0))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(BorderStroke(responsiveDp(1.dp), border.copy(alpha = 0.4f)), RoundedCornerShape(responsiveDp(8.dp)))
                    .padding(responsiveDp(8.dp))
            ) {
                items(rows) { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = responsiveDp(3.dp))) {
                        Text("$currencySymbol${row.amount.toPlainString()}", color = border, fontSize = responsiveSp(13.0), modifier = Modifier.weight(1f))
                        Text(row.count.toString(), color = border, fontSize = responsiveSp(13.0), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text("$currencySymbol${row.total.toPlainString()}", color = border, fontSize = responsiveSp(13.0), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, settings: Settings, modifier: Modifier = Modifier) {
    val border = Color(settings.buttonBorderColor)
    Column(
        modifier = modifier
            .border(BorderStroke(responsiveDp(1.dp), border.copy(alpha = 0.4f)), RoundedCornerShape(responsiveDp(8.dp)))
            .padding(horizontal = responsiveDp(10.dp), vertical = responsiveDp(8.dp))
    ) {
        Text(label, color = border.copy(alpha = 0.7f), fontSize = responsiveSp(10.0), lineHeight = responsiveSp(12.0))
        Text(value, color = border, fontSize = responsiveSp(16.0), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BarChart(bars: List<DonationStats.Bar>, timeframe: Timeframe, settings: Settings) {
    val border = Color(settings.buttonBorderColor)
    val fill = Color(settings.buttonColor)
    val max = bars.maxOfOrNull { it.total }?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(responsiveDp(140.dp))
            .border(BorderStroke(responsiveDp(1.dp), border.copy(alpha = 0.4f)), RoundedCornerShape(responsiveDp(8.dp)))
            .padding(responsiveDp(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (bars.isEmpty()) return@Canvas
            val gap = 4f
            val w = (size.width - gap * (bars.size + 1)) / bars.size
            bars.forEachIndexed { i, bar ->
                val ratio = if (max > BigDecimal.ZERO) bar.total.toFloat() / max.toFloat() else 0f
                val h = (size.height * ratio).coerceAtLeast(0f)
                val left = gap + i * (w + gap)
                drawRect(
                    color = fill,
                    topLeft = Offset(left, size.height - h),
                    size = Size(w, h)
                )
                drawRect(
                    color = border.copy(alpha = 0.6f),
                    topLeft = Offset(left, size.height - h),
                    size = Size(w, h),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }
        }
        // Axis labels: first + last bucket
        if (bars.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(barLabel(bars.first().labelTimestampMs, timeframe), color = border.copy(alpha = 0.6f), fontSize = responsiveSp(9.0), modifier = Modifier.weight(1f))
                Text(barLabel(bars.last().labelTimestampMs, timeframe), color = border.copy(alpha = 0.6f), fontSize = responsiveSp(9.0), textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    settings: Settings,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = rememberStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(settings.backgroundColor),
        title = { Text(title, color = if (destructive) Color.Red else Color(settings.buttonBorderColor), fontWeight = FontWeight.Bold) },
        text = { Text(body, color = Color(settings.buttonBorderColor)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = if (destructive) Color.Red else Color(0xFFFFA000))
            ) {
                Text(confirmText, color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                Text(strings.cancel, color = Color.White)
            }
        }
    )
}

// ── Date formatters ──────────────────────────────────────────────────────────

private fun shortDate(ms: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun longDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun barLabel(ms: Long, timeframe: Timeframe): String {
    val pattern = when (timeframe) {
        Timeframe.TODAY -> "HH:mm"
        Timeframe.LAST_7_DAYS, Timeframe.LAST_30_DAYS -> "d MMM"
        Timeframe.LAST_YEAR -> "MMM"
        Timeframe.ALL_TIME -> "MMM yyyy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
