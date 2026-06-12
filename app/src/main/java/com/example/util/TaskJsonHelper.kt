package com.example.util

import com.example.data.Task
import org.json.JSONArray
import org.json.JSONObject

object TaskJsonHelper {
    fun exportTasksToJson(tasks: List<Task>): String {
        val jsonArray = JSONArray()
        for (task in tasks) {
            val jsonObject = JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("description", task.description)
                put("startTime", task.startTime)
                put("recurrence", task.recurrence)
                put("isCompleted", task.isCompleted)
                put("isAccepted", task.isAccepted)
                put("descriptionImageUri", task.descriptionImageUri ?: "")
                put("completionImageUri", task.completionImageUri ?: "")
                put("completionTime", task.completionTime ?: -1L)
                put("dateCreated", task.dateCreated)
            }
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString(4) // Ident with 4 spaces
    }

    fun importTasksFromJson(jsonString: String): List<Task> {
        val tasks = mutableListOf<Task>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val descImage = jsonObject.optString("descriptionImageUri", "")
                val compImage = jsonObject.optString("completionImageUri", "")
                val compTime = jsonObject.optLong("completionTime", -1L)

                val task = Task(
                    // Do non-zero ID if we want to overwrite, but inserting might generate new IDs to prevent conflict,
                    // or respect imported IDs. Let's strip ID or use 0 to treat as new tasks, or retain ID depending on use.
                    // To overwrite / merge cleanly, we can retain imported ID (using 0 to generate a new task if ID is missing).
                    id = jsonObject.optLong("id", 0L),
                    title = jsonObject.optString("title", "Untitled Task"),
                    description = jsonObject.optString("description", ""),
                    startTime = jsonObject.optLong("startTime", System.currentTimeMillis() + 60000),
                    recurrence = jsonObject.optString("recurrence", "NONE"),
                    isCompleted = jsonObject.optBoolean("isCompleted", false),
                    isAccepted = jsonObject.optBoolean("isAccepted", false),
                    descriptionImageUri = if (descImage.isBlank()) null else descImage,
                    completionImageUri = if (compImage.isBlank()) null else compImage,
                    completionTime = if (compTime == -1L) null else compTime,
                    isBeeping = false,
                    dateCreated = jsonObject.optLong("dateCreated", System.currentTimeMillis())
                )
                tasks.add(task)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tasks
    }
}
