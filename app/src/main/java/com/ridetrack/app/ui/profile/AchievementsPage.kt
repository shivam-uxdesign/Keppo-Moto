package com.ridetrack.app.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridetrack.app.share.ShareImages
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.components.PrimaryButton
import com.ridetrack.app.ui.components.RtCard
import com.ridetrack.app.ui.components.SecondaryButton
import com.ridetrack.app.ui.format.Format
import com.ridetrack.app.ui.home.BikeCare
import com.ridetrack.app.ui.nav.LocalNavigate
import com.ridetrack.app.ui.nav.Routes
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Everything the Achievements page shows. */
data class AchievementsData(
    val list: List<Achievement>,
    val inputs: AchievementInputs,
    val level: RiderLevel,
    val records: List<Record>,
    val year: Map<LocalDate, Double>,
    val streak: Pair<Int, Int>,
)

/** Reads what the badges need (rides, their facts, clips, fills, Reels…); facts for new rides are worked out a few at a time. */
suspend fun loadAchievementInputs(c: com.ridetrack.app.AppContainer, factsBudget: Int = 6): AchievementInputs = withContext(Dispatchers.IO) {
    val settings = c.settings.settings.first()
    val reels = c.reels.reels.value.filter { it.deletedAt == null }
    val rides = c.rides.observeCompleted().first()
    val series = c.series.series.value
    val bikes = c.bikes.observeBikes().first()
    AchievementInputs(
        rides = rides,
        reelTimes = reels.map { it.createdAt },
        seriesEpisodes = reels.count { it.seriesId != null },
        fillTimes = com.ridetrack.app.fuel.Fuel.decodeFills(settings.fuelLog).map { it.timeMillis },
        careReminders = BikeCare.decode(settings.bikeCare).size,
        crashAlertsReady = settings.safety.crashDetection && settings.safety.contacts.isNotEmpty(),
        mountCalibrated = bikes.any { it.calibration != null },
        facts = runCatching { RideFactsStore(c).load(rides.filter { it.source != com.ridetrack.telemetry.model.DataSourceKind.DEMO }.map { it.id }, factsBudget) }.getOrDefault(emptyMap()),
        moments = c.moments.allLive().filter { it.kind == com.ridetrack.app.moments.MomentKind.CLIP }
            .map { m -> MomentFact(m.rideId, m.timeMillis, com.ridetrack.app.moments.MomentKeywords.shown(m.keywords.orEmpty()).map { it.key }.toSet()) },
        bikes = bikes.map { BikeFact(it.id, "${it.make} ${it.model}".trim(), it.odometerKm) },
        seriesCount = series.size,
        storyboardsFilled = reels.count { it.template != null && com.ridetrack.app.studio.storyboard.TemplateBuilder.placeholders(it.plan).isEmpty() },
        seriesWithEpisodes = series.count { s -> reels.any { it.seriesId == s.id } },
    )
}

/** The rider's achievements; null while loading. */
@Composable
fun rememberAchievements(): List<Achievement>? = rememberAchievementData()?.list

@Composable
fun rememberAchievementData(): AchievementsData? {
    val c = appContainer()
    var data by remember { mutableStateOf<AchievementsData?>(null) }
    LaunchedEffect(Unit) {
        // Twice: at once with what's known, then again once new rides' facts are in.
        repeat(2) { pass ->
            val i = loadAchievementInputs(c, factsBudget = if (pass == 0) 0 else 8)
            val zone = ZoneId.systemDefault()
            val list = Achievements.of(i, zone)
            data = AchievementsData(list, i, Achievements.level(list), Achievements.records(i, zone), Achievements.ridingYear(i.rides, LocalDate.now(zone).year, zone), Achievements.streak(i.rides, zone))
        }
    }
    return data
}

/** "7 of 22 earned" for Profile's row. */
fun achievementsSummary(list: List<Achievement>?): String =
    list?.let { l -> "${Achievements.level(l).name} · ${l.count(Achievement::earned)} of ${l.size} badges" } ?: " "

/**
 * Achievements: the rider's level, the badges closest to their next level, the riding year, badges
 * by group (drawn medallions, Bronze to Diamond), records and the setup checklist. Tap a badge for
 * its story and a card to share.
 */
@Composable
fun AchievementsPage() {
    val d = rememberAchievementData() ?: return
    val nav = LocalNavigate.current
    var open by remember { mutableStateOf<Achievement?>(null) }
    val now = System.currentTimeMillis()
    val month = d.list.count { (it.earnedAt ?: 0L) > now - 30L * 86_400_000L }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LevelCard(d.level, d.list.count { it.earned }, d.list.size, month)
        val next = Achievements.nextUp(d.list)
        if (next.isNotEmpty()) {
            Section("Next up")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                next.forEach { a ->
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(18.dp)).background(RtColors.Surface).clickable(role = Role.Button) { open = a }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BadgeMedallion(a, 52.dp)
                        Text(a.title, style = RtType.bodyStrong, color = RtColors.TextPrimary, maxLines = 2)
                        Text(a.left().orEmpty(), style = RtType.caption, color = RtColors.TextSecondary)
                    }
                }
            }
        }
        Section("Your riding year")
        RtCard { YearGrid(d.year, d.streak) }
        d.list.groupBy { it.group }.forEach { (group, items) ->
            Section(group)
            BadgeGrid(items) { open = it }
        }
        if (d.records.isNotEmpty()) {
            Section("Records")
            RtCard {
                d.records.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .then(if (r.rideId != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the ride") { nav(Routes.detail(r.rideId)) } else Modifier)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = RtType.body, color = RtColors.TextPrimary)
                            r.atMillis?.let { Text(Format.rideDate(it), style = RtType.caption, color = RtColors.TextTertiary) }
                        }
                        Text(r.value, style = RtType.bodyStrong, color = RtColors.TextPrimary)
                    }
                }
            }
        }
        Section("Ready to ride")
        RtCard {
            Achievements.setup(d.inputs).forEach { (text, done) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (done) RtColors.Ok else RtColors.TextTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = RtType.body, color = if (done) RtColors.TextPrimary else RtColors.TextSecondary)
                }
            }
        }
    }
    open?.let { a -> BadgeSheet(a, onClose = { open = null }) }
}

private fun km(m: Double) = "${amount(com.ridetrack.app.ui.format.Units.distance(m))} ${com.ridetrack.app.ui.format.Units.distanceUnit}"

@Composable
private fun Section(text: String) = Text(text.uppercase(), style = RtType.label, color = RtColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))

/** The level ring: Rookie → Rider → Tourer → Road Captain → Legend. */
@Composable
private fun LevelCard(level: RiderLevel, earned: Int, all: Int, thisMonth: Int) {
    val primary = RtColors.Primary
    val track = RtColors.Outline
    RtCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(96.dp)) {
                    val w = 9.dp.toPx()
                    drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
                    drawArc(primary, -90f, 360f * level.fraction, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
                }
                Text("${level.points}", style = RtType.headline, color = RtColors.TextPrimary)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(level.name, style = RtType.title, color = RtColors.TextPrimary)
                Text(level.to?.let { "${it - level.points} points to ${Achievements.LEVELS.first { l -> l.second == it }.first}" } ?: "The top level", style = RtType.caption, color = RtColors.TextSecondary)
                Text("$earned of $all badges" + if (thisMonth > 0) " · $thisMonth this month" else "", style = RtType.caption, color = RtColors.TextSecondary)
            }
        }
    }
}

/** One square per day of the year, coloured by distance; and the weekend streak. */
@Composable
private fun YearGrid(year: Map<LocalDate, Double>, streak: Pair<Int, Int>) {
    val today = LocalDate.now()
    val start = LocalDate.of(today.year, 1, 1)
    val primary = RtColors.Primary
    val empty = RtColors.Outline.copy(alpha = 0.6f)
    val max = year.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val days = year.size
    val km = year.values.sum()
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(53f / 7f).semantics { contentDescription = "$days riding days this year" },
    ) {
        val cell = size.width / 53f
        val gap = cell * 0.18f
        var d = start
        while (d.year == today.year && !d.isAfter(today)) {
            val week = (d.dayOfYear - 1 + start.dayOfWeek.value - 1) / 7
            val dow = d.dayOfWeek.value - 1
            val v = year[d]
            val color = if (v == null) empty else primary.copy(alpha = (0.35f + 0.65f * (v / max).toFloat()).coerceIn(0.35f, 1f))
            drawRoundRect(color, Offset(week * cell, dow * cell), Size(cell - gap, cell - gap), androidx.compose.ui.geometry.CornerRadius(gap, gap))
            d = d.plusDays(1)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("$days riding days · ${amount(km)} ${com.ridetrack.app.ui.format.Units.distanceUnit}", style = RtType.bodyStrong, color = RtColors.TextPrimary)
    Text("Weekend streak: ${streak.first} now · best ${streak.second}", style = RtType.caption, color = RtColors.TextSecondary)
}

/** Badges three to a row. */
@Composable
private fun BadgeGrid(items: List<Achievement>, onOpen: (Achievement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { a ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClickLabel = "Open the badge") { onOpen(a) }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BadgeMedallion(a, 64.dp)
                        Text(if (a.secret && !a.earned) "Secret" else a.title, style = RtType.caption, color = if (a.earned) RtColors.TextPrimary else RtColors.TextSecondary, textAlign = TextAlign.Center, maxLines = 2)
                        Text(
                            when {
                                a.secret && !a.earned -> "Keep riding"
                                a.tier != null -> a.tier!!.label
                                a.earned -> "Earned"
                                else -> a.left() ?: ""
                            },
                            style = RtType.caption, color = RtColors.TextTertiary, textAlign = TextAlign.Center, maxLines = 1,
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A badge up close: the tilt shine, when and where it was earned, the next tier, and a card to share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgeSheet(a: Achievement, onClose: () -> Unit) {
    val nav = LocalNavigate.current
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val tilt = rememberTilt()
    val layer = rememberGraphicsLayer()
    var ride by remember { mutableStateOf<com.ridetrack.telemetry.model.Ride?>(null) }
    LaunchedEffect(a.rideId) { ride = a.rideId?.let { id -> withContext(Dispatchers.IO) { c.rides.get(id) } } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = RtColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BadgeMedallion(a, 150.dp, shine = tilt)
            Text(if (a.secret && !a.earned) "A secret badge" else a.title, style = RtType.title, color = RtColors.TextPrimary, textAlign = TextAlign.Center)
            Text(if (a.secret && !a.earned) "It shows itself when you earn it." else a.detail, style = RtType.body, color = RtColors.TextSecondary, textAlign = TextAlign.Center)
            if (a.earned) {
                Text(
                    listOfNotNull(a.tier?.label, a.earnedAt?.let { "Earned ${Format.rideDate(it)}" }).joinToString(" · "),
                    style = RtType.bodyStrong, color = RtColors.TextPrimary,
                )
                ride?.let { r ->
                    Text("${r.name} · ${km(r.stats.distanceM)} · ${Format.duration(r.durationMillis)}", style = RtType.caption, color = RtColors.TextSecondary, textAlign = TextAlign.Center)
                }
            }
            a.next?.let { n ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(RtColors.Outline)) {
                        Box(Modifier.fillMaxWidth(a.fraction).height(8.dp).clip(RoundedCornerShape(4.dp)).background(RtColors.Primary))
                    }
                    Text(
                        (if (a.tiered) "${Tier.entries[a.level.coerceAtMost(4)].label}: " else "") + "${amount(a.progress)} of ${amount(n)} ${a.unit}".trim(),
                        style = RtType.caption, color = RtColors.TextSecondary,
                    )
                }
            }
            if (a.earned) {
                // The card to share, drawn here and captured as a picture.
                Box(
                    Modifier.width(180.dp).aspectRatio(9f / 16f).drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
                ) { ShareCard(a, ride) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Share", {
                        scope.launch {
                            val bmp = layer.toImageBitmap().asAndroidBitmap()
                            val uri = ShareImages.toCacheUri(context, bmp, "badge-${a.id}.png")
                            ShareImages.share(context, uri)
                        }
                    }, Modifier.weight(1f))
                    a.rideId?.let { id -> SecondaryButton("Open ride", { onClose(); nav(Routes.detail(id)) }, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A Stories card for a badge: the medallion, its name and tier, the ride, in the app's colour. */
@Composable
fun ShareCard(a: Achievement, ride: com.ridetrack.telemetry.model.Ride?) {
    val accent = RtColors.Primary
    Box(
        Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Color(0xFF0A0A0B), Color(0xFF0A0A0B)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(12.dp)) {
            BadgeMedallion(a, 84.dp)
            Text(a.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            a.tier?.let { Text(it.label.uppercase(), color = Color(it.argb), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            ride?.let { r ->
                Text(r.name, color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2)
                Text("${km(r.stats.distanceM)} · ${Format.duration(r.durationMillis)}", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp)
            }
        }
        Text("KEPPO MOTO", color = Color.White.copy(alpha = 0.5f), fontSize = 7.sp, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}
