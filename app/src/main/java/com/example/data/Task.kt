package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val startTime: Long, // scheduled start time (timestamp)
    val recurrence: String, // "NONE", "DAILY", "WEEKLY", "MONTHLY"
    val isCompleted: Boolean = false,
    val isAccepted: Boolean = false,
    val descriptionImageUri: String? = null,
    val completionImageUri: String? = null,
    val completionTime: Long? = null,
    val isBeeping: Boolean = false, // true if currently triggering
    val dateCreated: Long = System.currentTimeMillis()
)
