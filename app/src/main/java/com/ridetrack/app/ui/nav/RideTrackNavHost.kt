package com.ridetrack.app.ui.nav

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
    const val BIKE = "bike"
    const val PROFILE = "profile"
    const val LIVE = "live"
    const val SUMMARY = "summary/{rideId}"
    const val DETAIL = "ride/{rideId}"
    const val BIKE_EDIT = "bike/edit?bikeId={bikeId}"
    const val CALIBRATE = "calibrate/{bikeId}"
    const val HUD_SETTINGS = "hud-settings"
    const val RIDES_MAP = "rides-map"
    const val SHARE = "share/{rideId}"
    fun share(id: String) = "share/$id"
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
    Tab(Routes.BIKE, "Bike", Icons.Outlined.TwoWheeler),
    Tab(Routes.PROFILE, "Profile", Icons.Outlined.Person),
)

@Composable
fun RideTrackNavHost() {
    val nav = rememberNavController()
    val session = appContainer().session
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = tabs.any { it.route == route }

    // If a ride is in progress (e.g. the activity was recreated), go straight back to it.
    LaunchedEffect(Unit) {
        if (session.state.value.isActive) nav.navigate(Routes.LIVE) { launchSingleTop = true }
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
                        onOpenBikes = { nav.switchTab(Routes.BIKE) },
                        onAddBike = { nav.navigate(Routes.bikeEdit()) },
                        onEditBike = { nav.navigate(Routes.bikeEdit(it)) },
                    )
                }
                composable(Routes.RIDES) {
                    RidesScreen(
                        onOpenRide = { nav.navigate(Routes.detail(it)) },
                        onStartRide = { nav.switchTab(Routes.HOME) },
                        onOpenMap = { nav.navigate(Routes.RIDES_MAP) },
                    )
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
                composable(Routes.PROFILE) { ProfileScreen(onOpenHudSettings = { nav.navigate(Routes.HUD_SETTINGS) }) }
                composable(Routes.HUD_SETTINGS) { HudSettingsScreen(onBack = { nav.popBackStack() }) }
                composable(
                    Routes.LIVE,
                    // The start button "opens up" into the live screen.
                    enterTransition = {
                        fadeIn(tween(300)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.92f)
                    },
                    exitTransition = { ExitTransition.None },
                ) {
                    LiveRideScreen(
                        onRideSaved = { id ->
                            nav.navigate(Routes.summary(id)) { popUpTo(Routes.HOME) }
                        },
                        onExit = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                    )
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
                        onOpenMoment = { nav.navigate(Routes.moments(id, it)) },
                    )
                }
                composable(Routes.DETAIL, arguments = listOf(navArgument("rideId") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("rideId").orEmpty()
                    RideDetailScreen(
                        rideId = id,
                        onBack = { nav.popBackStack() },
                        onShare = { nav.navigate(Routes.share(id)) },
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
                    MomentViewerScreen(
                        rideId = rideId,
                        startId = entry.arguments?.getString("momentId"),
                        onBack = { nav.popBackStack() },
                        onShareMoment = { nav.navigate(Routes.momentShare(rideId, it)) },
                    )
                }
                composable(
                    Routes.MOMENT_SHARE,
                    arguments = listOf(
                        navArgument("rideId") { type = NavType.StringType },
                        navArgument("momentId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    MomentShareScreen(
                        rideId = entry.arguments?.getString("rideId").orEmpty(),
                        momentId = entry.arguments?.getString("momentId").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.SHARE, arguments = listOf(navArgument("rideId") { type = NavType.StringType })) { entry ->
                    ShareRideScreen(rideId = entry.arguments?.getString("rideId").orEmpty(), onBack = { nav.popBackStack() })
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
