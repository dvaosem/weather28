package com.dvaosem.weather28.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/** Exact daily alarm for the smart forecast notification (same approach as MorningAlarmReceiver). */
class SmartAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = OneTimeWorkRequestBuilder<WeatherAlertWorker>()
            .setInputData(workDataOf("type" to "smart"))
            .build()
        WorkManager.getInstance(context).enqueue(work)
        AlertScheduler.scheduleSmartAlarm(context) // tomorrow's alarm
    }
}
