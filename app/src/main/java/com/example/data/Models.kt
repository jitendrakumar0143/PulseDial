package com.example.data

import android.net.Uri

data class CallLogEntry(
    val id: Long,
    val name: String?,
    val number: String,
    val type: Int,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val photoUri: String? = null
)

data class ContactEntry(
    val contactId: Long,
    val name: String,
    val number: String,
    val isStarred: Boolean,
    val photoUri: String? = null
)

data class RecordingEntry(
    val uri: Uri,
    val displayName: String,
    val timestampMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val sourceLabel: String
)
