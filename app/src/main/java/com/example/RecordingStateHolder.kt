package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RecordingStateHolder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _currentSegmentStartTime = MutableStateFlow(0L)
    val currentSegmentStartTime = _currentSegmentStartTime.asStateFlow()

    private val _currentSegmentFileName = MutableStateFlow("")
    val currentSegmentFileName = _currentSegmentFileName.asStateFlow()

    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime = _sessionStartTime.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    fun onRecordingStarted(segmentName: String, startTime: Long) {
        if (_sessionStartTime.value == 0L) {
            _sessionStartTime.value = startTime
        }
        _currentSegmentStartTime.value = startTime
        _currentSegmentFileName.value = segmentName
        _isRecording.value = true
        _lastError.value = null
    }

    fun onSegmentRotated(newSegmentName: String, newStartTime: Long) {
        _currentSegmentStartTime.value = newStartTime
        _currentSegmentFileName.value = newSegmentName
    }

    fun onRecordingStopped() {
        _isRecording.value = false
        _currentSegmentStartTime.value = 0L
        _currentSegmentFileName.value = ""
        _sessionStartTime.value = 0L
    }

    fun onError(errorMessage: String) {
        _lastError.value = errorMessage
        _isRecording.value = false
        _currentSegmentStartTime.value = 0L
    }
}
