package com.example.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsRepository(private val context: Context) {

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWriteCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWriteContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun getRecentCalls(limit: Int = 100): List<CallLogEntry> = withContext(Dispatchers.IO) {
        if (!hasCallLogPermission()) return@withContext emptyList()

        val result = mutableListOf<CallLogEntry>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_PHOTO_URI
        )

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val photoIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI)

                while (cursor.moveToNext() && result.size < limit) {
                    val number = cursor.getString(numIdx).orEmpty()
                    if (number.isBlank()) continue
                    val photoUri = if (photoIdx >= 0) {
                        cursor.getString(photoIdx)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    result.add(
                        CallLogEntry(
                            id = cursor.getLong(idIdx),
                            name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() },
                            number = number,
                            type = cursor.getInt(typeIdx),
                            timestampMillis = cursor.getLong(dateIdx),
                            durationSeconds = cursor.getLong(durIdx),
                            photoUri = photoUri
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }
        result
    }

    suspend fun getCallHistoryForNumber(phoneNumber: String, limit: Int = 50): List<CallLogEntry> =
        withContext(Dispatchers.IO) {
            if (!hasCallLogPermission()) return@withContext emptyList()
            val targetDigits = phoneNumber.filter { it.isDigit() }
            if (targetDigits.isEmpty()) return@withContext emptyList()

            val allCalls = getRecentCalls(limit = 500)
            allCalls.filter { entry ->
                numbersMatch(targetDigits, entry.number.filter { it.isDigit() })
            }.take(limit)
        }

    suspend fun deleteSingleCallLog(callLogId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasWriteCallLogPermission()) return@withContext false
        try {
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(callLogId.toString())
            )
            deleted > 0
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun deleteCallHistoryForNumber(phoneNumber: String): Int = withContext(Dispatchers.IO) {
        if (!hasWriteCallLogPermission() || !hasCallLogPermission()) return@withContext 0
        val matchingEntries = getCallHistoryForNumber(phoneNumber, limit = 1000)
        if (matchingEntries.isEmpty()) return@withContext 0

        var totalDeleted = 0
        try {
            matchingEntries.chunked(100).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val selection = "${CallLog.Calls._ID} IN ($placeholders)"
                val args = chunk.map { it.id.toString() }.toTypedArray()
                totalDeleted += context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    selection,
                    args
                )
            }
        } catch (_: SecurityException) {
            return@withContext totalDeleted
        }
        totalDeleted
    }

    suspend fun deleteAllCallHistory(): Int = withContext(Dispatchers.IO) {
        if (!hasWriteCallLogPermission()) return@withContext 0
        try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        } catch (_: SecurityException) {
            0
        }
    }

    suspend fun getContacts(onlyStarred: Boolean = false): List<ContactEntry> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext emptyList()

        val result = mutableListOf<ContactEntry>()
        val seenKeys = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )
        val selection = if (onlyStarred) "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1" else null

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val starIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: "Unknown"
                    val number = cursor.getString(numIdx)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val photoUri = if (photoIdx >= 0) {
                        cursor.getString(photoIdx)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    val dedupKey = "$contactId:${number.filter { it.isDigit() }}"
                    if (seenKeys.add(dedupKey)) {
                        result.add(
                            ContactEntry(
                                contactId = contactId,
                                name = name,
                                number = number,
                                isStarred = cursor.getInt(starIdx) == 1,
                                photoUri = photoUri
                            )
                        )
                    }
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        }
        result
    }

    suspend fun lookupContactByNumber(phoneNumber: String): ContactEntry? = withContext(Dispatchers.IO) {
        if (!hasContactsPermission() || phoneNumber.isBlank()) return@withContext null
        val targetDigits = phoneNumber.filter { it.isDigit() }
        if (targetDigits.isEmpty()) return@withContext null
        return@withContext getContacts(onlyStarred = false).firstOrNull { entry ->
            numbersMatch(targetDigits, entry.number.filter { it.isDigit() })
        }
    }

    suspend fun saveNewContact(name: String, phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasWriteContactsPermission()) return@withContext false
        val cleanName = name.trim()
        val cleanNumber = phoneNumber.trim()
        if (cleanName.isEmpty() || cleanNumber.isEmpty()) return@withContext false

        val ops = ArrayList<ContentProviderOperation>().apply {
            add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        cleanName
                    )
                    .build()
            )
            add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanNumber)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    )
                    .build()
            )
        }

        return@withContext try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun setContactStarred(contactId: Long, starred: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!hasWriteContactsPermission()) return@withContext false
        return@withContext try {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            val updated = context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString())
            )
            updated > 0
        } catch (_: Exception) {
            false
        }
    }

    fun lookupCallerName(number: String): String? {
        if (!hasContactsPermission() || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun numbersMatch(digitsA: String, digitsB: String): Boolean {
            if (digitsA.isEmpty() || digitsB.isEmpty()) return false
            if (digitsA == digitsB) return true
            val minLen = minOf(digitsA.length, digitsB.length)
            return if (minLen >= 7) {
                digitsA.takeLast(minOf(10, minLen)) == digitsB.takeLast(minOf(10, minLen))
            } else {
                false
            }
        }
    }
}
