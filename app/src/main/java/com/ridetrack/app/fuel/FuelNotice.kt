package com.ridetrack.app.fuel

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.launch
import java.util.Locale

/** "Saved fill · ₹500 · 4.6 L at ₹103.44 (Pune)", with Undo. */
object FuelNotice {
    private const val CHANNEL = "fuel_saved"
    const val ACTION_UNDO = "com.keppo.moto.FUEL_UNDO"

    @SuppressLint("MissingPermission")
    fun saved(context: Context, fill: FuelFill, prompt: FuelPrompt, price: PetrolPrice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fuel", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Fill-ups saved from the card SMS and today's price" })
        }
        val id = fill.id.hashCode()
        val undo = Intent(context, FuelUndoReceiver::class.java).setAction(ACTION_UNDO)
            .putExtra("fill", fill.id).putExtra("prompt", Fuel.encodePrompts(listOf(prompt))).putExtra("notification", id)
        val pi = PendingIntent.getBroadcast(context, id, undo, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = (if (price.exact) "" else "about ") + "₹" + String.format(Locale.US, "%.2f", price.perLitre) + (price.place.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle("Saved fill · ${Fuel.money(fill.amount ?: 0.0)} · ${String.format(Locale.US, "%.2f", fill.litres)} L")
            .setContentText("At $at, ${fill.station ?: "petrol pump"}" + if (price.exact) "" else " · today's price wasn't found")
            .addAction(0, "Undo", pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** Undo on the "Saved fill" notification. */
class FuelUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FuelNotice.ACTION_UNDO) return
        val fill = intent.getStringExtra("fill") ?: return
        val prompt = Fuel.decodePrompts(intent.getStringExtra("prompt")).firstOrNull() ?: return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notification", 0))
        val c = (context.applicationContext as RideTrackApp).container
        val done = goAsync()
        c.appScope.launch {
            try { c.fuel.undoAuto(fill, prompt) } finally { done.finish() }
        }
    }
}
