package com.ridetrack.app.ui.nav

import com.ridetrack.app.data.RideLookup
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.ridetrack.app.ui.trash.RecentlyDeletedScreen
import com.ridetrack.app.ui.theme.RideDark
import com.ridetrack.app.ui.theme.LocalRtPalette
import com.ridetrack.app.ui.theme.DarkPalette
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import android.app.Activity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ridetrack.app.ui.appContainer
import com.ridetrack.app.ui.bike.BikeEditScreen
import com.ridetrack.app.ui.bike.BikeScreen
import com.ridetrack.app.ui.bike.CalibrationScreen
import com.ridetrack.app.ui.detail.RideDetailScreen
import com.ridetrack.app.ui.home.HomeScreen
import com.ridetrack.app.ui.hud.HudSettingsScreen
import com.ridetrack.app.ui.live.LiveRideScreen
import com.ridetrack.app.ui.profile.ProfileScreen
import com.ridetrack.app.ui.profile.ProfilePageScreen
import com.ridetrack.app.ui.profile.ProfilePage
import com.ridetrack.app.share.MomentShareScreen
import com.ridetrack.app.share.ShareRideScreen
import com.ridetrack.app.ui.moments.MomentViewerScreen
import com.ridetrack.app.ui.rides.RidesMapScreen
import com.ridetrack.app.ui.rides.RidesScreen
import com.ridetrack.app.ui.summary.RideSummaryScreen
import com.ridetrack.app.ui.theme.RtColors
import com.ridetrack.app.ui.theme.RtType

object Routes {
    const val HOME = "home"
    const val RIDES = "rides"
    const val STUDIO = "studio"
    const val BIKE = "bike"
    const val PROFILE = "profile"
    const val LIVE = "live"
    const val SUMMARY = "summary/{rideId}"
    const val DETAIL = "ride/{rideId}"
    const val BIKE_EDIT = "bike/edit?bikeId={bikeId}"
    const val CALIBRATE = "calibrate/{bikeId}"
    const val HUD_SETTINGS = "hud-settings"
    const val PROFILE_PAGE = "profile/{page}"
    fun profilePage(page: ProfilePage) = "profile/${page.route}"
    const val RECENTLY_DELETED = "recently-deleted?rideId={rideId}"
    fun recentlyDeleted(rideId: String? = null) = if (rideId == null) "recently-deleted" else "recently-deleted?rideId=$rideId"
    const val RIDES_MAP = "rides-map"
    const val SHARE = "share/{rideId}?mode={mode}&reel={reel}"
    fun share(id: String) = "share/$id"
    /** A ride's Studio page. */
    const val RIDE_STUDIO = "studio/{rideId}?reel={reel}&idea={idea}&make={make}&edit={edit}"
    fun reel(id: String) = "studio/$id"
    /** One of a ride's ideas, straight in the editor (or made now, in the background). */
    fun idea(rideId: String, title: String, make: Boolean = false) = "studio/$rideId?idea=${android.net.Uri.encode(title)}" + if (make) "&make=true" else ""
    /** A saved Reel, opened in its ride's Studio. */
    fun savedReel(rideId: String, reelId: String, edit: Boolean = false) = "studio/$rideId?reel=$reelId" + if (edit) "&edit=true" else ""
    /** Studio › Your Reels. */
    const val REELS = "reels"
    /** Studio › Your style. */
    const val STUDIO_STYLE = "studio-style"
    /** Studio › Styles: every style, the brand kit, the style editor. */
    const val STYLES = "styles"
    /** Studio › Settings. */
    const val STUDIO_SETTINGS = "studio-settings"
    /** A series' folder (its episodes) and its settings; [NEW_SERIES] makes one. */
    /** Ready videos full screen to share, one after another; at [reel], or only [ride]'s. */
    const val REVIEW = "studio-review?reel={reel}&ride={ride}"
    fun review(reel: String? = null, ride: String? = null) = "studio-review" + listOfNotNull(reel?.let { "reel=$it" }, ride?.let { "ride=$it" }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    const val SERIES = "studio-series/{id}"
    const val NEW_SERIES = "new"
    fun series(id: String) = "studio-series/$id"
    /** Studio › Search: what was said or seen in every clip. */
    const val STUDIO_SEARCH = "studio-search"
    /** Keppo Moto Pro: the paywall. */
    const val PAYWALL = "pro"
    /** Studio with videos from the phone only (no ride). */
    const val STUDIO_PHONE = "studio-phone?reel={reel}&edit={edit}"
    fun studioPhone(reelId: String?, edit: Boolean = false) = "studio-phone" + (reelId?.let { "?reel=$it" + if (edit) "&edit=true" else "" } ?: "")
    const val MOMENTS = "moments/{rideId}/{momentId}"
    fun moments(rideId: String, momentId: String) = "moments/$rideId/$momentId"
    const val MOMENT_SHARE = "moment-share/{rideId}/{momentId}"
    fun momentShare(rideId: String, momentId: String) = "moment-share/$rideId/$momentId"

    fun summary(id: String) = "summary/$id"
    fun detail(id: String) = "ride/$id"
    fun bikeEdit(id: String? = null) = if (id == null) "bike/edit" else "bike/edit?bikeId=$id"
    fun calibrate(bikeId: String) = "calibrate/$bikeId"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.RIDES, "Rides", Icons.Outlined.History),
    Tab(Routes.STUDIO, "Studio", Icons.Outlined.Movie),
    Tab(Routes.BIKE, "Bike", Icons.Outlined.TwoWheeler),
    Tab(Routes.PROFILE, "Profile", Icons.Outlined.Person),
)

/** Opens a route from deep inside a screen (e.g. Studio › Your style). */
val LocalNavigate = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/** What an "open this ride" request may look like: [prefix:]id[#reel], ids of letters, digits, '-', '_' and '.'. */
internal val OPEN_RIDE_SHAPE = Regex("^(?:[a-z]+:)?[A-Za-z0-9._-]{0,100}(?:#[A-Za-z0-9._-]{1,100})?$")

@Composable
fun RideTrackNavHost(openRideId: String? = null, onOpenRideHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    val container = appContainer()
    val session = container.session
    val context = LocalContext.current
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = tabs.any { it.route == route }

    // If a ride is in progress (e.g. the activity was recreated), go straight back to it.
    LaunchedEffect(Unit) {
        if (session.state.value.isActive) nav.navigate(Routes.LIVE) { launchSingleTop = true }
    }

    // OPEN_RIDE from Keppo Journal (or any app): the ride, Recently deleted, or the rides list.
    LaunchedEffect(openRideId) {
        val asked = openRideId ?: return@LaunchedEffect
        onOpenRideHandled()
        // From other apps and notifications: only plain ids, so nothing odd ends up in a page address.
        if (!OPEN_RIDE_SHAPE.matches(asked)) return@LaunchedEffect
        if (session.state.value.isActive) return@LaunchedEffect
        // "Today's post": ready videos full screen, at that one.
        if (asked.startsWith(com.ridetrack.app.MainActivity.REVIEW_PREFIX)) {
            nav.navigate(Routes.review(asked.removePrefix(com.ridetrack.app.MainActivity.REVIEW_PREFIX)))
            return@LaunchedEffect
        }
        val studio = asked.startsWith(com.ridetrack.app.MainActivity.STUDIO_PREFIX)
        val id = asked.removePrefix(com.ridetrack.app.MainActivity.STUDIO_PREFIX).substringBefore('#')
        // A Reel (from "How did it do?"): open it.
        asked.substringAfter('#', "").takeIf { studio && it.isNotEmpty() }?.let { reel ->
            nav.navigate(if (id.isEmpty()) Routes.studioPhone(reel) else Routes.savedReel(id, reel))
            return@LaunchedEffect
        }
        when (container.rides.lookup(id)) {
            RideLookup.LIVE -> nav.navigate(if (studio) Routes.reel(id) else Routes.detail(id))
            RideLookup.DELETED -> nav.navigate(Routes.recentlyDeleted(id))
            RideLookup.MISSING -> {
                nav.navigate(Routes.RIDES) { popUpTo(Routes.HOME); launchSingleTop = true }
                Toast.makeText(context, "That ride isn't on this phone", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = RtColors.Background,
        bottomBar = { if (showBottomBar) BottomBar(nav, route) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalNavigate provides { r -> nav.navigate(r) }) {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(120)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(120)) },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onRideStarted = { nav.navigate(Routes.LIVE) { launchSingleTop = true } },
                        onReturnToRide = { nav.navigate(Routes.LIVE) { launchSingleTop = true } },
                        onOpenProfile = { nav.switchTab(Routes.PROFILE) },
                        onAddBike = { nav.navigate(Routes.bikeEdit()) },
                        onEditBike = { nav.navigate(Routes.bikeEdit(it)) },
                        onOpenRide = { nav.navigate(Routes.detail(it)) },
                        onShareRide = { nav.navigate(Routes.share(it)) },
                        onMakeReel = { nav.navigate(Routes.reel(it)) },
                        onOpenBike = { nav.switchTab(Routes.BIKE) },
                    )
                }
                composable(Routes.RIDES) {
                    RidesScreen(
                        onOpenRide = { nav.navigate(Routes.detail(it)) },
                        onStartRide = { nav.switchTab(Routes.HOME) },
                        onOpenMap = { nav.navigate(Routes.RIDES_MAP) },
                    )
                }
                composable(Routes.STUDIO) {
                    // Dark like the rest of Studio (and the prototype), whatever the phone's theme.
                    DarkRoute { com.ridetrack.app.studio.StudioHomeScreen(
                        onOpenRide = { nav.navigate(Routes.reel(it)) },
                        onOpenIdea = { ride, title, make -> nav.navigate(Routes.idea(ride, title, make)) },
                        onOpenPhone = { reel -> nav.navigate(Routes.studioPhone(reel)) },
                        onOpenReels = { nav.navigate(Routes.REELS) },
                        onOpenStyles = { nav.navigate(Routes.STYLES) },
                        onOpenSearch = { nav.navigate(Routes.STUDIO_SEARCH) },
                        onOpenSettings = { nav.navigate(Routes.STUDIO_SETTINGS) },
                        onOpenPaywall = { nav.navigate(Routes.PAYWALL) },
                    ) }
                }
                composable(Routes.RIDES_MAP) {
                    RidesMapScreen(onBack = { nav.popBackStack() }, onOpenRide = { nav.navigate(Routes.detail(it)) })
                }
                composable(Routes.BIKE) {
                    BikeScreen(
                        onAddBike = { nav.navigate(Routes.bikeEdit()) },
                        onEditBike = { nav.navigate(Routes.bikeEdit(it)) },
                        onCalibrate = { nav.navigate(Routes.calibrate(it)) },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onOpenPage = { nav.navigate(Routes.profilePage(it)) },
                        onOpenHudSettings = { nav.navigate(Routes.HUD_SETTINGS) },
                        onOpenRecentlyDeleted = { nav.navigate(Routes.recentlyDeleted()) },
                        onOpenPaywall = { nav.navigate(Routes.PAYWALL) },
                    )
                }
                composable(Routes.PAYWALL) {
                    DarkRoute { com.ridetrack.app.ui.pro.PaywallScreen(onClose = { nav.popBackStack() }) }
                }
                composable(
                    Routes.RECENTLY_DELETED,
                    arguments = listOf(navArgument("rideId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    RecentlyDeletedScreen(focusRideId = entry.arguments?.getString("rideId"), onBack = { nav.popBackStack() })
                }
                composable(Routes.HUD_SETTINGS) { HudSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.PROFILE_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) { entry ->
                    val page = ProfilePage.of(entry.arguments?.getString("page"))
                    if (page == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        ProfilePageScreen(page, onBack = { nav.popBackStack() })
                    }
                }
                composable(
                    Routes.LIVE,
                    // The start button "opens up" into the live screen.
                    enterTransition = {
                        fadeIn(tween(300)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f)
                    },
                    exitTransition = { ExitTransition.None },
                ) {
                    DarkRoute { LiveRideScreen(
                        onRideSaved = { id ->
                            nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) }
                        },
                        onExit = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                    ) }
                }
                composable(
                    Routes.SUMMARY,
                    arguments = listOf(navArgument("rideId") { type = NavType.StringType }),
                    enterTransition = { fadeIn(tween(400)) },
                ) { entry ->
                    val id = entry.arguments?.getString("rideId").orEmpty()
                    RideSummaryScreen(
                        rideId = id,
                        onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                        onOpenDetail = { nav.navigate(Routes.detail(id)) { popUpTo(Routes.HOME) } },
                        onShare = { nav.navigate(Routes.share(id)) },
                        onMakeReel = { nav.navigate(Routes.reel(id)) },
                        onOpenMoment = { nav.navigate(Routes.moments(id, it)) },
                    )
                }
                composable(Routes.DETAIL, arguments = listOf(navArgument("rideId") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("rideId").orEmpty()
                    RideDetailScreen(
                        rideId = id,
                        onBack = { nav.popBackStack() },
                        onShare = { nav.navigate(Routes.share(id)) },
                        onStudio = { nav.navigate(Routes.reel(id)) },
                        onOpenMoment = { nav.navigate(Routes.moments(id, it)) },
                    )
                }
                composable(
                    Routes.MOMENTS,
                    arguments = listOf(
                        navArgument("rideId") { type = NavType.StringType },
                        navArgument("momentId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val rideId = entry.arguments?.getString("rideId").orEmpty()
                    DarkRoute {
                        MomentViewerScreen(
                            rideId = rideId,
                            startId = entry.arguments?.getString("momentId"),
                            onBack = { nav.popBackStack() },
                            onShareMoment = { nav.navigate(Routes.momentShare(rideId, it)) },
                        )
                    }
                }
                composable(
                    Routes.MOMENT_SHARE,
                    arguments = listOf(
                        navArgument("rideId") { type = NavType.StringType },
                        navArgument("momentId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    DarkRoute {
                        MomentShareScreen(
                            rideId = entry.arguments?.getString("rideId").orEmpty(),
                            momentId = entry.arguments?.getString("momentId").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
                composable(Routes.REELS) {
                    DarkRoute {
                        com.ridetrack.app.studio.ReelsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenReel = { ride, reel, edit -> nav.navigate(Routes.savedReel(ride, reel, edit)) },
                            onOpenPhone = { reel, edit -> nav.navigate(Routes.studioPhone(reel, edit)) },
                            onStyle = { nav.navigate(Routes.STUDIO_STYLE) },
                            onSeries = { nav.navigate(Routes.series(it)) },
                        )
                    }
                }
                composable(Routes.STUDIO_SEARCH) {
                    DarkRoute { com.ridetrack.app.studio.StudioSearchScreen(onBack = { nav.popBackStack() }, onOpenRide = { nav.navigate(Routes.detail(it)) }, onOpenStudio = { nav.navigate(Routes.reel(it)) }) }
                }
                composable(
                    Routes.REVIEW,
                    arguments = listOf(
                        navArgument("reel") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("ride") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    DarkRoute {
                        com.ridetrack.app.studio.ReviewScreen(
                            start = entry.arguments?.getString("reel"),
                            rideId = entry.arguments?.getString("ride"),
                            onBack = { nav.popBackStack() },
                            onEdit = { p -> if (p.rideId != null) nav.navigate(Routes.savedReel(p.rideId, p.id, edit = true)) else nav.navigate(Routes.studioPhone(p.id, edit = true)) },
                        )
                    }
                }
                composable(Routes.SERIES, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    DarkRoute {
                        com.ridetrack.app.studio.SeriesScreen(
                            id = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onOpenReel = { ride, reel -> nav.navigate(Routes.savedReel(ride, reel)) },
                            onOpenPhone = { reel -> nav.navigate(Routes.studioPhone(reel)) },
                        )
                    }
                }
                composable(Routes.STUDIO_SETTINGS) {
                    DarkRoute { com.ridetrack.app.studio.StudioSettingsScreen(onBack = { nav.popBackStack() }) }
                }
                composable(Routes.STYLES) {
                    DarkRoute { com.ridetrack.app.studio.StylesScreen(onBack = { nav.popBackStack() }) }
                }
                composable(Routes.STUDIO_STYLE) {
                    DarkRoute { com.ridetrack.app.studio.StyleScreen(onBack = { nav.popBackStack() }) }
                }
                composable(
                    Routes.STUDIO_PHONE,
                    arguments = listOf(
                        navArgument("reel") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("edit") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    DarkRoute {
                        com.ridetrack.app.studio.PhoneStudioScreen(reelId = entry.arguments?.getString("reel"), onBack = { nav.popBackStack() }, editNow = entry.arguments?.getString("edit") == "true", onUnlock = { nav.navigate(Routes.PAYWALL) })
                    }
                }
                composable(
                    Routes.SHARE,
                    arguments = listOf(
                        navArgument("rideId") { type = NavType.StringType },
                        navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("reel") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    DarkRoute {
                        ShareRideScreen(
                            rideId = entry.arguments?.getString("rideId").orEmpty(),
                            onBack = { nav.popBackStack() },
                            startMode = entry.arguments?.getString("mode"),
                        )
                    }
                }
                composable(
                    Routes.RIDE_STUDIO,
                    arguments = listOf(
                        navArgument("rideId") { type = NavType.StringType },
                        navArgument("reel") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("idea") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("make") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("edit") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    DarkRoute {
                        com.ridetrack.app.studio.RideStudioScreen(
                            rideId = entry.arguments?.getString("rideId").orEmpty(),
                            reelId = entry.arguments?.getString("reel"),
                            idea = entry.arguments?.getString("idea"),
                            makeNow = entry.arguments?.getString("make") == "true",
                            editNow = entry.arguments?.getString("edit") == "true",
                            onBack = { nav.popBackStack() },
                            onUnlock = { nav.navigate(Routes.PAYWALL) },
                        )
                    }
                }
                composable(
                    Routes.BIKE_EDIT,
                    arguments = listOf(navArgument("bikeId") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    enterTransition = { EnterTransition.None },
                ) { entry ->
                    BikeEditScreen(
                        bikeId = entry.arguments?.getString("bikeId"),
                        onDone = { nav.popBackStack() },
                    )
                }
                composable(Routes.CALIBRATE, arguments = listOf(navArgument("bikeId") { type = NavType.StringType })) { entry ->
                    CalibrationScreen(bikeId = entry.arguments?.getString("bikeId").orEmpty(), onDone = { nav.popBackStack() })
                }
            }
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = RtColors.Hairline)
        NavigationBar(containerColor = RtColors.Background, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                val selected = current == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { nav.switchTab(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label, style = RtType.caption.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RtColors.Primary,
                        selectedTextColor = RtColors.TextPrimary,
                        indicatorColor = RtColors.Primary.copy(alpha = 0.16f),
                        unselectedIconColor = RtColors.TextSecondary,
                        unselectedTextColor = RtColors.TextSecondary,
                    ),
                )
            }
        }
    }
}

/**
 * Riding and media screens: dark in every theme (true colours for photos and video, no glare
 * while riding), with light status-bar icons while shown.
 */
@Composable
private fun DarkRoute(content: @Composable () -> Unit) {
    val view = LocalView.current
    val appDark = LocalRtPalette.current.isDark
    DisposableEffect(appDark) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = !appDark
            bars?.isAppearanceLightNavigationBars = !appDark
        }
    }
    RideDark {
        Box(Modifier.fillMaxSize().background(DarkPalette.background)) { content() }
    }
}
