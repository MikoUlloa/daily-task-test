package com.example.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.example.data.Task
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReportHelper {

    fun generateWeeklyReportPdf(context: Context, tasks: List<Task>): File? {
        val pdfDocument = PdfDocument()
        val file = File(context.cacheDir, "Custodian_Weekly_Report.pdf")

        // 612x792 is standard Letter size in postscript points (72 points / inch)
        val pageWidth = 612
        val pageHeight = 792
        var pageNumber = 1

        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        // Set up paints
        val titlePaint = Paint().apply {
            color = Color.rgb(136, 14, 79) // Deep Maroon-Purple
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val subTitlePaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val headerPaint = Paint().apply {
            color = Color.rgb(33, 150, 243) // Sky Blue
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val labelPaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val successPaint = Paint().apply {
            color = Color.rgb(56, 142, 60) // Forest Green
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val alertPaint = Paint().apply {
            color = Color.rgb(211, 47, 47) // Red Accent
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var y = 40f

        // 1. Draw QSAC Brand Top Header
        // Representing the QSAC graduation logo
        // Draw blue graduation cap rest on "Q" letters
        val qsacPaint = Paint().apply {
            color = Color.rgb(150, 20, 60) // QSAC Maroon
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        canvas.drawText("QSAC", 40f, y + 25f, qsacPaint)

        // Draw Graduation cap accent
        val capPaint = Paint().apply {
            color = Color.rgb(0, 150, 200) // Blue
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val path = android.graphics.Path()
        path.moveTo(40f, y)
        path.lineTo(65f, y - 10f)
        path.lineTo(90f, y)
        path.lineTo(65f, y + 10f)
        path.close()
        canvas.drawPath(path, capPaint)

        canvas.drawText("SCHOOLS FOR STUDENTS WITH AUTISM", 40f, y + 42f, subTitlePaint)
        
        y += 65f
        canvas.drawText("Custodian Daily Task Report", 40f, y, titlePaint)

        val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
        val generatedDate = dateFormat.format(Date())
        y += 18f
        canvas.drawText("Generated: $generatedDate", 40f, y, subTitlePaint)
        canvas.drawText("Report Email: thirty5tech@gmail.com", 40f, y + 14f, subTitlePaint)

        y += 40f

        // STATS SECTION
        val completedCount = tasks.count { it.isCompleted }
        val pendingCount = tasks.count { !it.isCompleted }
        val totalCount = tasks.size

        canvas.drawRect(40f, y - 15f, 572f, y + 35f, borderPaint)
        canvas.drawText("REPORT SUMMARY", 50f, y, labelPaint)
        canvas.drawText("Total Tasks: $totalCount", 50f, y + 20f, textPaint)
        canvas.drawText("Completed: $completedCount", 220f, y + 20f, successPaint)
        canvas.drawText("Pending: $pendingCount", 380f, y + 20f, alertPaint)

        y += 55f

        // Draw line separator
        canvas.drawLine(40f, y, 572f, y, borderPaint)
        y += 25f

        fun verifyPageSpace(needed: Float) {
            if (y + needed > pageHeight - 50f) {
                // Not enough room, wrap to new page!
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas

                // Reset paints & y coordinate on fresh page
                y = 50f
                canvas.drawText("Custodian Daily Task Report (Page $pageNumber)", 40f, y, labelPaint)
                canvas.drawLine(40f, y + 5f, 572f, y + 5f, borderPaint)
                y += 25f
            }
        }

        // 2. Draw COMPLETED TASKS
        verifyPageSpace(30f)
        canvas.drawText("COMPLETED TASKS (${completedCount})", 40f, y, headerPaint)
        canvas.drawLine(40f, y + 5f, 572f, y + 5f, borderPaint)
        y += 25f

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val completedTasks = tasks.filter { it.isCompleted }

        if (completedTasks.isEmpty()) {
            verifyPageSpace(20f)
            canvas.drawText("No completed tasks in this cycle.", 40f, y, subTitlePaint)
            y += 25f
        } else {
            for (task in completedTasks) {
                verifyPageSpace(100f)
                
                // Draw a beautiful subtle background card for task item
                val outlinePaint = Paint().apply {
                    color = Color.rgb(240, 248, 240) // Very pale green
                    style = Paint.Style.FILL
                }
                canvas.drawRect(40f, y - 10f, 572f, y + 60f, outlinePaint)
                canvas.drawRect(40f, y - 10f, 572f, y + 60f, borderPaint)

                canvas.drawText("✓  ${task.title}", 50f, y + 10f, successPaint)
                
                val schTimeStr = timeFormat.format(Date(task.startTime))
                val compTimeStr = if (task.completionTime != null) timeFormat.format(Date(task.completionTime)) else "Unknown"
                canvas.drawText("Scheduled: $schTimeStr | Completed: $compTimeStr | Repeat: ${task.recurrence}", 50f, y + 25f, subTitlePaint)

                val desc = if (task.description.length > 85) task.description.substring(0, 82) + "..." else task.description
                canvas.drawText("Desc: ${desc.ifBlank { "No description" }}", 50f, y + 40f, textPaint)

                // Images feedback marker
                val imgFeedback = StringBuilder()
                if (task.descriptionImageUri != null) imgFeedback.append("[Desc Pic Attachment] ")
                if (task.completionImageUri != null) imgFeedback.append("[Completion Pic Saved]")
                if (imgFeedback.isNotEmpty()) {
                    canvas.drawText(imgFeedback.toString(), 50f, y + 52f, labelPaint)
                }

                y += 80f
            }
        }

        y += 10f

        // 3. Draw OUTSTANDING TASKS
        verifyPageSpace(30f)
        canvas.drawText("PENDING & INCOMPLETE TASKS (${pendingCount})", 40f, y, headerPaint)
        canvas.drawLine(40f, y + 5f, 572f, y + 5f, borderPaint)
        y += 25f

        val pendingTasks = tasks.filter { !it.isCompleted }
        if (pendingTasks.isEmpty()) {
            verifyPageSpace(20f)
            canvas.drawText("Great job! All systems complete. No outstanding tasks.", 40f, y, subTitlePaint)
            y += 25f
        } else {
            for (task in pendingTasks) {
                verifyPageSpace(90f)

                val outlinePaint = Paint().apply {
                    color = Color.rgb(255, 245, 245) // Very pale red
                    style = Paint.Style.FILL
                }
                canvas.drawRect(40f, y - 10f, 572f, y + 50f, outlinePaint)
                canvas.drawRect(40f, y - 10f, 572f, y + 50f, borderPaint)

                canvas.drawText("⏳  ${task.title}", 50f, y + 10f, alertPaint)
                
                val schTimeStr = timeFormat.format(Date(task.startTime))
                canvas.drawText("Scheduled: $schTimeStr | Repeat: ${task.recurrence} | Accepted: ${if (task.isAccepted) "Yes" else "No"}", 50f, y + 25f, subTitlePaint)

                val desc = if (task.description.length > 85) task.description.substring(0, 82) + "..." else task.description
                canvas.drawText("Desc: ${desc.ifBlank { "No description" }}", 50f, y + 40f, textPaint)

                y += 70f
            }
        }

        // Draw Page footer
        verifyPageSpace(30f)
        y = pageHeight - 40f
        canvas.drawText("Unified Saturday Custodian Report - Confidential Report.", 40f, y, subTitlePaint)
        canvas.drawText("QSAC Administration Systems", pageWidth - 200f, y, subTitlePaint)

        pdfDocument.finishPage(page)

        try {
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            Log.d("PdfReportHelper", "Successfully saved PDF report containing ${tasks.size} tasks")
            return file
        } catch (e: Exception) {
            Log.e("PdfReportHelper", "Error writing PDF file", e)
        } finally {
            pdfDocument.close()
        }

        return null
    }
}
