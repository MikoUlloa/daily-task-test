package com.example.alarm

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object BeepPlayer {
    private const val TAG = "BeepPlayer"
    private var toneGenerator: ToneGenerator? = null
    private var beepJob: Job? = null

    @Synchronized
    fun startBeeping() {
        if (beepJob != null) return // Already beeping

        Log.d(TAG, "Starting system ToneGenerator beep loop")
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator, fallback to default stream", e)
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback ToneGenerator also failed", ex)
            }
        }

        beepJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing tone", e)
                }
                delay(1200) // Delay between beeps
            }
        }
    }

    @Synchronized
    fun stopBeeping() {
        Log.d(TAG, "Stopping system ToneGenerator beep loop")
        beepJob?.cancel()
        beepJob = null
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ToneGenerator", e)
        }
        toneGenerator = null
    }
}
