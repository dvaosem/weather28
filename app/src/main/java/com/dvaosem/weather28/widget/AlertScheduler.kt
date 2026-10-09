package com.dvaosem.weather28.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AlertScheduler {

    fun reschedule(ctx: Context) {
        // Ranné zhrnutie – presný alarm o konkrétnej hodine (WorkManager PeriodicWork
        // vie meškať 20-40+ min kvôli Doze/battery optimization, preto AlarmManager)
        if (AlertPrefs.morningEnabled(ctx)) {
            scheduleMorningAlarm(ctx)
        } else {
            cancelMorningAlarm(ctx)
        }

        // Búrky – kontrola každé 2 hodiny, presnosť na minútu tu nie je kritická
        val wm = WorkManager.getInstance(ctx)
        if (AlertPrefs.stormEnabled(ctx)) {
            val storm = PeriodicWorkRequestBuilder<WeatherAlertWorker>(2, TimeUnit.HOURS)
                .setInputData(workDataOf("type" to "storm"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork("w28_storm", ExistingPeriodicWorkPolicy.UPDATE, storm)
        } else {
            wm.cancelUniqueWork("w28_storm")
        }
    }

    private fun morningPendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MorningAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleMorningAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val hour = AlertPrefs.morningHour(ctx)
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = morningPendingIntent(ctx)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } else {
                // používateľ ešte nepovolil "Alarms & reminders" (Android 13+) –
                // naplánuj aspoň približne, presnosť sa vráti po povolení v nastaveniach appky
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
    }

    fun cancelMorningAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(morningPendingIntent(ctx))
    }
}
