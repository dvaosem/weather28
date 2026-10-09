package com.dvaosem.weather28.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Fired by an exact AlarmManager alarm at the user's configured morning time.
 * WorkManager's own PeriodicWorkRequest is only "approximate" (Doze/battery
 * optimization can shift it by 20-40+ minutes), so the actual notification
 * work still runs via a one-time WorkManager job (needs network + a background
 * thread), but the *timing* is driven by an exact alarm instead.
 */
class MorningAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = OneTimeWorkRequestBuilder<WeatherAlertWorker>()
            .setInputData(workDataOf("type" to "morning"))
            .build()
        WorkManager.getInstance(context).enqueue(work)

        // naplánuj rovnaký budík na zajtra
        AlertScheduler.scheduleMorningAlarm(context)
    }
}
