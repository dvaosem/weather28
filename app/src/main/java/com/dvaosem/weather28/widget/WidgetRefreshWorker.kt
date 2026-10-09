package com.dvaosem.weather28.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(applicationContext, WeatherWidget::class.java)
        )
        for (id in ids) {
            WeatherWidget.updateWidget(applicationContext, manager, id)
        }
        return Result.success()
    }
}
