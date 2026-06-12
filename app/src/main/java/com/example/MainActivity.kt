package com.example

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.Task
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            Log.d("MainActivity", "Permission ${it.key} granted: ${it.value}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle possible launch from Notification tap
        val alarmTaskId = intent.getLongExtra("ALARM_TRIGGERED_TASK_ID", -1L)
        if (alarmTaskId != -1L) {
            Log.d("MainActivity", "Launched via alarm notification for task $alarmTaskId")
        }

        requestAppPermissions()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold")
                ) { innerPadding ->
                    CustodianApp(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun requestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val needed = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}

// Media file copier to internal files directory for robust persistence
fun copyUriToAppStorage(context: Context, uri: Uri, prefix: String): String? {
    return try {
        val inputStream1 = context.contentResolver.openInputStream(uri) ?: return null
        val mediaDir = File(context.filesDir, "custodian_media").apply { mkdirs() }
        val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
        val outputFile = File(mediaDir, filename)
        
        inputStream1.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.d("MainActivity", "Saved uri internally to: ${outputFile.absolutePath}")
        outputFile.absolutePath
    } catch (e: Exception) {
        Log.e("MainActivity", "Error copying Uri internally", e)
        null
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CustodianApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tasks by viewModel.tasksState.collectAsStateWithLifecycle()
    val currentFilter by viewModel.filterState.collectAsStateWithLifecycle()
    val feedbackMsg by viewModel.feedbackMessage.collectAsStateWithLifecycle()

    // Dialog state controllers
    var isNewTaskSheetOpen by remember { mutableStateOf(false) }
    var taskToCompleteDetail by remember { mutableStateOf<Task?>(null) }
    var inspectPhotoPath by remember { mutableStateOf<String?>(null) }

    // SharedPreferences for persistent Custom Logo URI
    val prefs = remember { context.getSharedPreferences("custodian_prefs", Context.MODE_PRIVATE) }
    var customLogoUriString by remember { mutableStateOf(prefs.getString("custom_logo_uri", null)) }

    // Floating context feedback toast
    LaunchedEffect(feedbackMsg) {
        feedbackMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearFeedback()
        }
    }

    // Interactive Photo Pickers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val copiedPath = copyUriToAppStorage(context, it, "custom_logo")
            if (copiedPath != null) {
                prefs.edit().putString("custom_logo_uri", copiedPath).apply()
                customLogoUriString = copiedPath
                viewModel.showFeedback("Custom app logo uploaded!")
            } else {
                viewModel.showFeedback("Failed to load uploaded logo.")
            }
        }
    }

    // JSON file Import pickers
    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val json = inputStream?.bufferedReader()?.use { reader -> reader.readText() }
                if (!json.isNullOrBlank()) {
                    viewModel.importFromJsonString(json)
                } else {
                    viewModel.showFeedback("Error: Uploaded file is empty.")
                }
            } catch (e: Exception) {
                viewModel.showFeedback("Failed to read JSON backup file.")
            }
        }
    }

    // Active Urgently trigger alarms
    val beepingTask = remember(tasks) { tasks.firstOrNull { it.isBeeping && !it.isCompleted && !it.isAccepted } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // App Core Brand Header in Geometric Balance Theme
            val headerBorderColor = MaterialTheme.colorScheme.outlineVariant
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = headerBorderColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    },
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // Logo row drawing OR custom uploaded image
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (customLogoUriString != null) {
                            // User physically uploaded custom logo, render via Coil!
                            AsyncImage(
                                model = File(customLogoUriString!!),
                                contentDescription = "Custom Logo",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .height(48.dp)
                                    .widthIn(max = 160.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        // Option to clear or overwrite logo
                                        prefs.edit().remove("custom_logo_uri").apply()
                                        customLogoUriString = null
                                        viewModel.showFeedback("Reverted to Default Logo.")
                                    }
                            )
                        } else {
                            // Fallback default beautiful QSAC vector branding logo - now styled with Geometric Balance accents
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    viewModel.showFeedback("Hold this section to upload your custom logo!")
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val capColor = MaterialTheme.colorScheme.primary
                                    val capBaseColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                    Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        // Draw graduation mortarboard cap symbol
                                        val capPath = Path().apply {
                                            moveTo(size.width * 0.1f, size.height * 0.5f)
                                            lineTo(size.width * 0.5f, size.height * 0.2f)
                                            lineTo(size.width * 0.9f, size.height * 0.5f)
                                            lineTo(size.width * 0.5f, size.height * 0.8f)
                                            close()
                                        }
                                        drawPath(
                                            path = capPath,
                                            color = capColor
                                        )
                                        // Cap Base Underneath
                                        val baseCapPath = Path().apply {
                                            moveTo(size.width * 0.3f, size.height * 0.65f)
                                            lineTo(size.width * 0.3f, size.height * 0.85f)
                                            quadraticTo(
                                                size.width * 0.5f, size.height * 0.95f,
                                                size.width * 0.7f, size.height * 0.85f
                                            )
                                            lineTo(size.width * 0.7f, size.height * 0.65f)
                                        }
                                        drawPath(
                                            path = baseCapPath,
                                            color = capBaseColor
                                        )
                                        // Yellow gold tassel hanging
                                        drawLine(
                                            color = Color(0xFFFFB703), // Gold
                                            start = Offset(size.width * 0.5f, size.height * 0.5f),
                                            end = Offset(size.width * 0.18f, size.height * 0.72f),
                                            strokeWidth = 3f,
                                            cap = StrokeCap.Round
                                        )
                                        drawCircle(
                                            color = Color(0xFFFFB703),
                                            radius = 3f,
                                            center = Offset(size.width * 0.18f, size.height * 0.72f)
                                        )
                                    }
                                }
 
                                Column {
                                    Text(
                                        text = "QSAC",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Text(
                                        text = "SCHOOLS FOR STUDENTS WITH AUTISM",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 7.sp,
                                        color = Color.Gray,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
 
                        // Toolbar controls styled as gray-100 rounded circular pills in Geometric Balance
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = { galleryLauncher.launch("image/*") },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .testTag("upload_logo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Upload Custom Logo",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
 
                            // Import json
                            IconButton(
                                onClick = { jsonImportLauncher.launch("application/json") },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilePresent,
                                    contentDescription = "Import Task File",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
 
                            // Export json backup via share sheets
                            IconButton(
                                onClick = {
                                    viewModel.getExportJsonString { json ->
                                        val backupFile = File(context.cacheDir, "custodian_tasks_backup.json")
                                        try {
                                            FileOutputStream(backupFile).use { it.write(json.toByteArray()) }
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "com.aistudio.custodiandailytask.xrqbkm.fileprovider",
                                                backupFile
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_SUBJECT, "Custodian Task File Backup")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Save Tasks Asset File"))
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Export Backup",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
 
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Custodian Daily Task",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Stats row cards - Styled in Geometric Balance theme
            val completedCount = remember(tasks) { tasks.count { it.isCompleted } }
            val pendingCount = remember(tasks) { tasks.count { !it.isCompleted } }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Total card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("TOTAL TASKS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${tasks.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Completed Card
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("COMPLETED", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("$completedCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                // Pending Card (Alert soft-red)
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text("PENDING", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("$pendingCount", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // PDF Weekly Report Banner Block
            SaturdayPdfWidget(viewModel = viewModel)

            // Filtering Tab Items row
            ScrollableTabRow(
                selectedTabIndex = currentFilter.ordinal,
                edgePadding = 16.dp,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = @Composable { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[currentFilter.ordinal]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                FilterType.entries.forEach { type ->
                    Tab(
                        selected = currentFilter == type,
                        onClick = { viewModel.setFilter(type) },
                        text = {
                            Text(
                                text = when (type) {
                                    FilterType.ALL -> "Show All"
                                    FilterType.PENDING -> "Incomplete"
                                    FilterType.COMPLETED -> "Completed"
                                    FilterType.REPEATING -> "Repeating"
                                },
                                color = if (currentFilter == type) MaterialTheme.colorScheme.primary else Color.Gray,
                                fontWeight = if (currentFilter == type) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            // Tasks List
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Task,
                            contentDescription = "Empty tasks",
                            tint = Color.LightGray,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No custodian tasks recorded yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "Tap the plus button below to schedule tasks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("task_lazy_column"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskListItem(
                            task = task,
                            onAccept = { viewModel.acceptTask(task) },
                            onCompleteClick = { taskToCompleteDetail = task },
                            onDelete = { viewModel.deleteTask(task) },
                            onInspectPhoto = { inspectPhotoPath = it }
                        )
                    }
                }
            }
        }

        // Action additions call Button
        FloatingActionButton(
            onClick = { isNewTaskSheetOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_task_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Scheduled Daily Task")
        }

        // Alarm Trigger ringing overlay (BEEPING SOUND SCREEN overlay)
        if (beepingTask != null) {
            UrgentAlertOverlay(
                task = beepingTask,
                onAccept = { viewModel.acceptTask(beepingTask) }
            )
        }

        // Add task Bottom Sheet (Full Screen Dialog Overlay)
        if (isNewTaskSheetOpen) {
            AddTaskDialog(
                onDismiss = { isNewTaskSheetOpen = false },
                onSave = { title, desc, startTime, recurrence, imageUriPath ->
                    viewModel.saveTask(
                        title = title,
                        description = desc,
                        startTime = startTime,
                        recurrence = recurrence,
                        descriptionImageUri = imageUriPath
                    )
                    isNewTaskSheetOpen = false
                }
            )
        }

        // Completion Dialog (Required completion photo attachment)
        if (taskToCompleteDetail != null) {
            CompleteTaskDialog(
                task = taskToCompleteDetail!!,
                onDismiss = { taskToCompleteDetail = null },
                onComplete = { imgPath ->
                    viewModel.completeTask(taskToCompleteDetail!!, imgPath)
                    taskToCompleteDetail = null
                }
            )
        }

        // Image inspect slider/viewer dialog popup
        if (inspectPhotoPath != null) {
            Dialog(
                onDismissRequest = { inspectPhotoPath = null }
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(8.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = File(inspectPhotoPath!!),
                                contentDescription = "Full picture preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 450.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { inspectPhotoPath = null },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Close Preview", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskListItem(
    task: Task,
    onAccept: () -> Unit,
    onCompleteClick: () -> Unit,
    onDelete: () -> Unit,
    onInspectPhoto: (String) -> Unit
) {
    val formatter = SimpleDateFormat("EEEE, hh:mm a", Locale.getDefault())
    val formatTime = formatter.format(Date(task.startTime))

    // Dynamic emoji parser based on words in the title
    val categoryIcon = remember(task.title) {
        val titleLower = task.title.lowercase()
        when {
            titleLower.contains("mop") || titleLower.contains("clean") || titleLower.contains("floor") || titleLower.contains("wax") || titleLower.contains("swee") -> "🧹"
            titleLower.contains("restock") || titleLower.contains("supply") || titleLower.contains("soap") || titleLower.contains("dispens") || titleLower.contains("paper") -> "🧴"
            titleLower.contains("trash") || titleLower.contains("waste") || titleLower.contains("garbage") || titleLower.contains("bin") || titleLower.contains("dump") -> "🗑️"
            titleLower.contains("window") || titleLower.contains("glass") || titleLower.contains("mirror") -> "🪟"
            titleLower.contains("disinfect") || titleLower.contains("wipe") || titleLower.contains("sanitize") -> "🧼"
            titleLower.contains("spill") || titleLower.contains("leak") || titleLower.contains("water") -> "🚨"
            else -> "📋"
        }
    }

    // Is this a critical pulsing trigger item?
    val isCritical = task.isBeeping && !task.isCompleted && !task.isAccepted

    val pulseAlpha by animateInfiniteTransition(
        initialValue = 0.4f,
        targetValue = 1.0f,
        duration = 1000
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}"),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCritical -> MaterialTheme.colorScheme.errorContainer
                task.isCompleted -> MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isCritical) 2.dp else 1.dp,
            color = when {
                isCritical -> MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)
                task.isCompleted -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCritical) 3.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Critical header row
            if (isCritical) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFBA1A1A), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "ACTION REQUIRED",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "ALARM RINGING...",
                        color = Color(0xFFBA1A1A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }

            // Main body row (Left Side: Icon Container, Right Side: Text & Actions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circle Badge Placeholder
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            color = when {
                                isCritical -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                task.isCompleted -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCritical) "🚨" else categoryIcon,
                        fontSize = 20.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Text labels details
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = task.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isCritical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // Delete Button
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete task",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = "Scheduled Time Icon",
                            tint = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTime,
                            fontSize = 11.sp,
                            color = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )

                        if (task.recurrence != "NONE") {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = task.recurrence,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // Description block (if exists)
            if (task.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = task.description,
                    color = if (isCritical) MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }

            // Media attachment displays row (if exists)
            if (task.descriptionImageUri != null || task.completionImageUri != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    task.descriptionImageUri?.let { path ->
                        Column {
                            Text("Instructions File", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Task description attached picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(55.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                                    .clickable { onInspectPhoto(path) }
                            )
                        }
                    }

                    task.completionImageUri?.let { path ->
                        Column {
                            Text("Completion Verification", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Task completed verification picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(55.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFC7E2C7), RoundedCornerShape(8.dp))
                                    .clickable { onInspectPhoto(path) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress status and actionable control buttons row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Status badge
                val isDark = isSystemInDarkTheme()
                val (statusText, statusBg, statusTextClr) = when {
                    task.isCompleted -> Triple(
                        "DONE", 
                        if (isDark) Color(0xFF1B5E20).copy(alpha = 0.2f) else Color(0xFFE2F3E2), 
                        if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                    )
                    task.isAccepted -> Triple(
                        "IN PROGRESS", 
                        if (isDark) Color(0xFFE65100).copy(alpha = 0.2f) else Color(0xFFFFF3DB), 
                        if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
                    )
                    else -> Triple(
                        "PENDING", 
                        if (isDark) Color(0xFFBA1A1A).copy(alpha = 0.2f) else Color(0xFFFEE8E6), 
                        if (isDark) Color(0xFFFF8A80) else Color(0xFFBA1A1A)
                    )
                }
                Box(
                    modifier = Modifier
                        .background(statusBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusTextClr
                    )
                }

                // Interactive Action buttons (Start, Complete, etc.)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!task.isAccepted && !task.isCompleted) {
                        Button(
                            onClick = onAccept,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCritical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isCritical) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start task indicator", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Accept Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (task.isAccepted && !task.isCompleted) {
                        Button(
                            onClick = onCompleteClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDark) Color(0xFF16501E) else Color(0xFF2E7D32),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Camera verify indicator", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Complete [Photo]", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Saturday PDF automatic scheduler indicator and email sender widget card
@Composable
fun SaturdayPdfWidget(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isTodaySaturday = remember {
        Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
    }

    val isDark = isSystemInDarkTheme()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFD6E3FF)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isDark) MaterialTheme.colorScheme.outlineVariant else Color(0xFFA8C7FF))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (isDark) MaterialTheme.colorScheme.surface else Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("📅", fontSize = 18.sp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isTodaySaturday) "Saturday Report Due!" else "Weekly Report Automated",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) MaterialTheme.colorScheme.onSecondaryContainer else Color(0xFF001B3D)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(if (isDark) MaterialTheme.colorScheme.surface else Color.White, RoundedCornerShape(10.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "PDF ACTIVE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isDark) MaterialTheme.colorScheme.secondary else Color(0xFF001B3D)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "Sending Sat to thirty5tech@gmail.com",
                    fontSize = 10.sp,
                    color = (if (isDark) MaterialTheme.colorScheme.onSecondaryContainer else Color(0xFF001B3D)).copy(alpha = 0.7f),
                    lineHeight = 13.sp
                )
            }

            Button(
                onClick = {
                    viewModel.handleGeneratePdf(context) { file ->
                        val emailRecipient = "thirty5tech@gmail.com"
                        val subjectText = "Custodian Task Status Weekly Report"
                        val bodyText = "Hello,\n\nPlease locate the attached PDF containing completed and incomplete task status details for Custodian Daily Task Manager."
                        
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "com.aistudio.custodiandailytask.xrqbkm.fileprovider",
                                file
                            )
                            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(emailRecipient))
                                putExtra(Intent.EXTRA_SUBJECT, subjectText)
                                putExtra(Intent.EXTRA_TEXT, bodyText)
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(emailIntent, "Send Saturday Report pdf..."))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Failed to launch device email chooser: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) MaterialTheme.colorScheme.primary else Color.White,
                    contentColor = if (isDark) MaterialTheme.colorScheme.onPrimary else Color(0xFF001B3D)
                ),
                border = BorderStroke(1.dp, if (isDark) MaterialTheme.colorScheme.outline else Color(0xFFA8C7FF)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Deliver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// URGENT ALERT RINGING SCREEN OVERLAY (Loud looping beeps inside app popup)
@Composable
fun UrgentAlertOverlay(
    task: Task,
    onAccept: () -> Unit
) {
    var animatePulsing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animatePulsing = true
    }

    val pulseScale by animateInfiniteTransition(
        initialValue = 0.95f,
        targetValue = 1.1f,
        duration = 1000
    )

    val flashBackgroundAlpha by animateInfiniteTransition(
        initialValue = 0.2f,
        targetValue = 0.45f,
        duration = 800
    )

    Dialog(
        onDismissRequest = {}, // Disallow clicking outside to dismiss
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Red.copy(alpha = flashBackgroundAlpha),
                                Color.Black.copy(alpha = 0.85f)
                            ),
                            center = center,
                            radius = size.minDimension * 0.9f
                        )
                    )
                }
                .testTag("alarm_overlay"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                // Large Pulsing Icon
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(pulseScale)
                        .background(Color.Red.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(75.dp)
                            .background(Color.Red, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Ringing alert icon",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                Text(
                    text = "URGENT ALARM START TIME",
                    color = Color.Red,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = task.title,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 34.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (task.description.isNotBlank()) {
                    Text(
                        text = task.description,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "🔈 Looping system buzzer will ring continuously until this task is Accepted / Started.",
                    color = Color(0xFFFFB700),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(36.dp))

                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green, contentColor = Color.Black),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(55.dp)
                        .testTag("accept_task_beep_button"),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.PlayCircleFilled, contentDescription = "Accept start button", modifier = Modifier.size(26.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "START TASK NOW",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

// Add Scheduled Task Bottom Full Overlay Dialog
@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Long, String, String?) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var selectedRecurrence by remember { mutableStateOf("NONE") }
    var taskDescImageLocalPath by remember { mutableStateOf<String?>(null) }

    // Date & Time Selectors State
    val calendar = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 2) } }
    var dateString by remember {
        mutableStateOf(SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time))
    }
    var timeString by remember {
        mutableStateOf(SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time))
    }

    // Photo picker for description attachment
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val localCopyPath = copyUriToAppStorage(context, it, "task_desc")
            if (localCopyPath != null) {
                taskDescImageLocalPath = localCopyPath
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp), // notch space padding fallback
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Schedule Custodian Task",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss scheduled popup")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title (e.g. Mop Lunch Hall)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_title_input"),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Description Input
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Task Instructions / Details") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Select Date & Time Pickers Row
                Text("Scheduled Alarm Time", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    calendar.set(Calendar.YEAR, year)
                                    calendar.set(Calendar.MONTH, month)
                                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                    dateString = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(calendar.time)
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant, 
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CalendarToday, contentDescription = "Pick Date icon", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(dateString, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hourOfDay, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                    calendar.set(Calendar.MINUTE, minute)
                                    timeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(calendar.time)
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                false
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant, 
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AccessTime, contentDescription = "Pick Time icon", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(timeString, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Recurrence Selector Dropdown UI
                Text("Task Recurrence Interval", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("NONE", "DAILY", "WEEKLY", "MONTHLY").forEach { recurOption ->
                        val isSelected = selectedRecurrence == recurOption
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedRecurrence = recurOption },
                            label = { Text(recurOption, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Optional Description Image Input
                Text("Instruction Image Attachment (Optional)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clickable { pickerLauncher.launch("image/*") },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    if (taskDescImageLocalPath != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = File(taskDescImageLocalPath!!),
                                contentDescription = "Attached task instruction preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp))
                                    .padding(4.dp)
                            ) {
                                Text("Click to Change", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(imageVector = Icons.Default.AddAPhoto, contentDescription = "Add Picture Icon", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Upload Description Image", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Gallery chooser or device camera", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                Toast.makeText(context, "Please enter a Task Title", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSave(
                                title,
                                desc,
                                calendar.timeInMillis,
                                selectedRecurrence,
                                taskDescImageLocalPath
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_task_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Add to Schedule", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Complete Task verification dialog
@Composable
fun CompleteTaskDialog(
    task: Task,
    onDismiss: () -> Unit,
    onComplete: (String?) -> Unit
) {
    val context = LocalContext.current
    var taskCompImageLocalPath by remember { mutableStateOf<String?>(null) }

    // Pick complete screenshot/photo launcher
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val localCopyPath = copyUriToAppStorage(context, it, "task_completion")
            if (localCopyPath != null) {
                taskCompImageLocalPath = localCopyPath
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val isDark = isSystemInDarkTheme()
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Verify Completion",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Provide a picture verifying work completion for: ${task.title}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Verification Image Select card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clickable { pickerLauncher.launch("image/*") },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    if (taskCompImageLocalPath != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = File(taskCompImageLocalPath!!),
                                contentDescription = "Attached task complete preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp))
                                    .padding(4.dp)
                            ) {
                                Text("Click to Change", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(imageVector = Icons.Default.CameraAlt, contentDescription = "Add Complete Picture Icon", tint = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Upload Completion Picture", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Take photo or record proof", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Back")
                    }

                    Button(
                        onClick = {
                            if (taskCompImageLocalPath == null) {
                                Toast.makeText(context, "Completion verification image is required", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onComplete(taskCompImageLocalPath)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDark) Color(0xFF16501E) else Color(0xFF2E7D32),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("submit_complete_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Confirm Finished", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Custom simple helper for infinite progress scaling animation state values
@Composable
fun animateInfiniteTransition(
    initialValue: Float,
    targetValue: Float,
    duration: Int
): State<Float> {
    val transition = rememberInfiniteTransition(label = "PulseTransition")
    return transition.animateFloat(
        initialValue = initialValue,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatAnimation"
    )
}
