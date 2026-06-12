package com.example.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.Task

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    fun scheduleAlarm(context: Context, task: Task) {
        if (task.isCompleted || task.isAccepted) return
        if (task.startTime < System.currentTimeMillis()) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("TASK_ID", task.id)
            action = "com.example.ACTION_START_TASK_ALARM_${task.id}"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // If it can schedule exact alarms, do so. Fallback if it throws SecurityException.
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startTime, pendingIntent)
                    Log.d(TAG, "Scheduled exact alarm for task ${task.id} at ${task.startTime}")
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startTime, pendingIntent)
                    Log.d(TAG, "Scheduled inexact alarm (exact permission denied) for task ${task.id}")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.startTime, pendingIntent)
                Log.d(TAG, "Scheduled exact alarm for task ${task.id}")
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, task.startTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling exact alarm for task ${task.id}, using default", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, task.startTime, pendingIntent)
        }
    }

    fun cancelAlarm(context: Context, task: Task) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.example.ACTION_START_TASK_ALARM_${task.id}"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled alarm for task ${task.id}")
        }
    }
}
