package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.AlarmScheduler
import com.example.alarm.BeepPlayer
import com.example.data.AppDatabase
import com.example.data.Task
import com.example.data.TaskRepository
import com.example.util.PdfReportHelper
import com.example.util.TaskJsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: TaskRepository
    
    // UI Filter selection
    private val _filterState = MutableStateFlow(FilterType.ALL)
    val filterState: StateFlow<FilterType> = _filterState.asStateFlow()

    // Import/Export and notifications toast feedback
    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    init {
        val taskDao = AppDatabase.getDatabase(application).taskDao()
        repository = TaskRepository(taskDao)
        
        // Observe changes to database and start/stop BeepPlayer reactively!
        viewModelScope.launch(Dispatchers.IO) {
            repository.allTasksFlow.collect { tasks ->
                val anyBeeping = tasks.any { it.isBeeping && !it.isCompleted && !it.isAccepted }
                if (anyBeeping) {
                    BeepPlayer.startBeeping()
                } else {
                    BeepPlayer.stopBeeping()
                }
            }
        }
    }

    // Combine all tasks and selected filter to produce filtered tasks StateFlow
    val tasksState: StateFlow<List<Task>> = repository.allTasksFlow
        .combine(_filterState) { list, filter ->
            when (filter) {
                FilterType.ALL -> list
                FilterType.PENDING -> list.filter { !it.isCompleted }
                FilterType.COMPLETED -> list.filter { it.isCompleted }
                FilterType.REPEATING -> list.filter { it.recurrence != "NONE" }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setFilter(filter: FilterType) {
        _filterState.value = filter
    }

    fun clearFeedback() {
        _feedbackMessage.value = null
    }

    fun showFeedback(msg: String) {
        _feedbackMessage.value = msg
    }

    // CREATE OR UPDATE TASK
    fun saveTask(
        id: Long = 0,
        title: String,
        description: String,
        startTime: Long,
        recurrence: String,
        descriptionImageUri: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // If updating an existing task, cancel his old alarm first
            if (id != 0L) {
                val existing = repository.getTaskById(id)
                if (existing != null) {
                    AlarmScheduler.cancelAlarm(getApplication(), existing)
                }
            }

            val newTask = Task(
                id = id,
                title = title.ifBlank { "Task" },
                description = description,
                startTime = startTime,
                recurrence = recurrence,
                descriptionImageUri = descriptionImageUri,
                isCompleted = false,
                isAccepted = false,
                isBeeping = false,
                completionImageUri = null,
                completionTime = null
            )

            val newId = repository.insertTask(newTask)
            val savedTask = newTask.copy(id = if (id == 0L) newId else id)

            // Schedule alarm for the start time
            AlarmScheduler.scheduleAlarm(getApplication(), savedTask)
            _feedbackMessage.value = "Task saved & alarm scheduled successfully."
        }
    }

    // ACCEPT/START TASK
    fun acceptTask(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = task.copy(isAccepted = true, isBeeping = false)
            repository.updateTask(updated)
            // Stop sound
            BeepPlayer.stopBeeping()
            _feedbackMessage.value = "Task accepted. Good luck!"
        }
    }

    // COMPLETE TASK
    fun completeTask(task: Task, completionImageUri: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            // Cancel existing alarm just in case
            AlarmScheduler.cancelAlarm(getApplication(), task)

            val updated = task.copy(
                isCompleted = true,
                isAccepted = true,
                isBeeping = false,
                completionImageUri = completionImageUri,
                completionTime = System.currentTimeMillis()
            )
            repository.updateTask(updated)

            // Handle Recurrence (schedule the NEXT task copy)
            if (task.recurrence != "NONE") {
                val nextStartTime = calculateNextRecurrenceTime(task.startTime, task.recurrence)
                if (nextStartTime > System.currentTimeMillis()) {
                    val repeatedTask = Task(
                        title = task.title,
                        description = task.description,
                        startTime = nextStartTime,
                        recurrence = task.recurrence,
                        descriptionImageUri = task.descriptionImageUri,
                        isCompleted = false,
                        isAccepted = false,
                        isBeeping = false
                    )
                    val newId = repository.insertTask(repeatedTask)
                    val savedRepeated = repeatedTask.copy(id = newId)
                    AlarmScheduler.scheduleAlarm(getApplication(), savedRepeated)
                    _feedbackMessage.value = "Task completed! Next occurrence scheduled."
                } else {
                    _feedbackMessage.value = "Task marked completed."
                }
            } else {
                _feedbackMessage.value = "Task marked completed."
            }
        }
    }

    // DELETE TASK
    fun deleteTask(task: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            AlarmScheduler.cancelAlarm(getApplication(), task)
            repository.deleteTask(task)
            _feedbackMessage.value = "Task deleted."
        }
    }

    // CALC NEXT SCHEDULED TIME FOR REPEATING ACTION
    private fun calculateNextRecurrenceTime(currentStartTime: Long, recurrenceParam: String): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = currentStartTime
        }
        val now = Calendar.getInstance()

        // Double check calendar has passed now, increment repeat units until we are in the future
        while (calendar.before(now) || calendar.timeInMillis <= System.currentTimeMillis()) {
            when (recurrenceParam.uppercase()) {
                "DAILY" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
                "WEEKLY" -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
                "MONTHLY" -> calendar.add(Calendar.MONTH, 1)
                else -> break
            }
        }
        return calendar.timeInMillis
    }

    // EXPORT TO JSON
    fun getExportJsonString(onReady: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasksList = repository.getAllTasks()
            val json = TaskJsonHelper.exportTasksToJson(tasksList)
            onReady(json)
        }
    }

    // IMPORT FROM JSON
    fun importFromJsonString(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val importedTasks = TaskJsonHelper.importTasksFromJson(json)
            if (importedTasks.isEmpty()) {
                _feedbackMessage.value = "Import Failed: Empty or invalid task file."
                return@launch
            }

            var count = 0
            for (task in importedTasks) {
                // Ensure ID is generated fresh, or overwrite if exists.
                // Let's check if the ID already exists in theDB.
                val existing = repository.getTaskById(task.id)
                if (existing != null) {
                    // Update & reschedule
                    AlarmScheduler.cancelAlarm(getApplication(), existing)
                    repository.updateTask(task.copy(isBeeping = false))
                    AlarmScheduler.scheduleAlarm(getApplication(), task)
                } else {
                    // Insert as new task
                    val freshTask = task.copy(id = 0, isBeeping = false)
                    val newId = repository.insertTask(freshTask)
                    val savedFresh = freshTask.copy(id = newId)
                    AlarmScheduler.scheduleAlarm(getApplication(), savedFresh)
                }
                count++
            }
            _feedbackMessage.value = "Imported $count tasks successfully!"
        }
    }

    // GENERATE SATURDAY PDF REPORT
    fun handleGeneratePdf(context: Context, onReady: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasksList = repository.getAllTasks()
            val file = PdfReportHelper.generateWeeklyReportPdf(context, tasksList)
            if (file != null) {
                onReady(file)
            } else {
                _feedbackMessage.value = "Failed to generate report PDF."
            }
        }
    }
}

enum class FilterType {
    ALL, PENDING, COMPLETED, REPEATING
}
