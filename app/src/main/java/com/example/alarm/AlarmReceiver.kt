package com.example.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "custodian_task_alarms_channel"
        private const val CHANNEL_NAME = "Custodian Task Alarms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("TASK_ID", -1L)
        Log.d(TAG, "Alarm triggered! Task ID: $taskId")
        if (taskId == -1L) return

        val appDatabase = AppDatabase.getDatabase(context)
        val taskDao = appDatabase.taskDao()

        CoroutineScope(Dispatchers.IO).launch {
            val task = taskDao.getTaskById(taskId)
            Log.d(TAG, "Fetched task: ${task?.title}, Completed: ${task?.isCompleted}, Accepted: ${task?.isAccepted}")

            // Only beep and notify if the task is NOT completed and NOT accepted
            if (task != null && !task.isCompleted && !task.isAccepted) {
                // Update task in database to signify it is currently beeping
                taskDao.updateTask(task.copy(isBeeping = true))

                // Create and show notification with ringtone
                showNotification(context, task.id, task.title, task.description)
            }
        }
    }

    private fun showNotification(context: Context, id: Long, title: String, description: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Create Navigation PendingIntent
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALARM_TRIGGERED_TASK_ID", id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        // Create Channel for Oreo+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    this.description = "Urgent alerts for start of Custodian Tasks"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                    
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    setSound(soundUri, audioAttributes)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Task Start Time: $title")
            .setContentText(description.ifBlank { "Task scheduled starting now." })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)

        // Trigger the notification
        val notification = builder.build()
        
        // FLAG_INSISTENT loops the ringtone until dismissed
        @Suppress("DEPRECATION")
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        notificationManager.notify(id.toInt(), notification)
    }
}
