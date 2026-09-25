package com.aiia.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aiia.app.R
import com.aiia.app.ui.MainActivity

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
        const val EXTRA_COMMAND = "com.aiia.app.widget.CMD"
        const val CMD_MEMORY = "cmd_memory"
        const val CMD_SEARCH = "cmd_search"
        const val CMD_CALL = "cmd_call"
        const val CMD_OPEN = "cmd_open"

        fun prefillFor(command: String?): String = when (command) {
            CMD_MEMORY -> "запомни: "
            CMD_SEARCH -> "найди в интернете: "
            CMD_CALL -> "позови: "
            else -> ""
        }
    }
}
