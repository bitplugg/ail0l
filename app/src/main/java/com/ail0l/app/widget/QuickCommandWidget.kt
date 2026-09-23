package com.ail0l.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ail0l.app.R
import com.ail0l.app.ui.MainActivity

/**
 * Виджет главного экрана с быстрыми командами.
 * Кнопки открывают чат и подставляют префикс команды в поле ввода.
 */
class QuickCommandWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_command)
            views.setOnClickPendingIntent(R.id.btn_memory, pending(context, CMD_MEMORY))
            views.setOnClickPendingIntent(R.id.btn_search, pending(context, CMD_SEARCH))
            views.setOnClickPendingIntent(R.id.btn_call, pending(context, CMD_CALL))
            views.setOnClickPendingIntent(R.id.btn_open, pending(context, CMD_OPEN))
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun pending(context: Context, command: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_COMMAND, command)
        }
        return PendingIntent.getActivity(
            context,
            command.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_COMMAND = "com.ail0l.app.widget.CMD"
        const val CMD_MEMORY = "cmd_memory"
        const val CMD_SEARCH = "cmd_search"
        const val CMD_CALL = "cmd_call"
        const val CMD_OPEN = "cmd_open"

        /** Префикс команды, который подставляется в поле ввода чата. */
        fun prefillFor(command: String?): String = when (command) {
            CMD_MEMORY -> "запомни: "
            CMD_SEARCH -> "найди в интернете: "
            CMD_CALL -> "позови: "
            else -> ""
        }
    }
}