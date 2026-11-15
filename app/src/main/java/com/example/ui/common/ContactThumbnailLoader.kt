package com.example.ui.common

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Loads circular contact profile thumbnails via ContentResolver with an in-memory LRU cache
 * and ViewHolder tag validation so fast RecyclerView scrolling never mismatches avatars.
 */
object ContactThumbnailLoader {

    private const val MAX_CACHE_ENTRIES = 64

    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_ENTRIES) {}
    private val missingKeys: MutableSet<String> =
        Collections.synchronizedSet(HashSet())

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    private val loaderScope = CoroutineScope(SupervisorJob())

    fun bindAvatar(
        imageView: ImageView,
        fallbackView: View,
        photoUri: String?,
        phoneNumber: String? = null,
        contactId: Long? = null,
        displayLabel: String? = null
    ) {
        val key = resolveCacheKey(photoUri, contactId, phoneNumber)
        imageView.tag = key
        imageView.contentDescription = displayLabel?.takeIf { it.isNotBlank() }

        if (key == null) {
            showFallback(imageView, fallbackView)
            return
        }

        val cached = bitmapCache.get(key)
        if (cached != null) {
            showBitmap(imageView, fallbackView, cached)
            return
        }

        if (missingKeys.contains(key)) {
            showFallback(imageView, fallbackView)
            return
        }

        showFallback(imageView, fallbackView)
        val appContext = imageView.context.applicationContext

        loaderScope.launch(ioDispatcher) {
            val loaded = fetchContactBitmap(appContext, photoUri, phoneNumber, contactId)
            if (loaded != null) {
                bitmapCache.put(key, loaded)
                missingKeys.remove(key)
            } else {
                missingKeys.add(key)
            }

            withContext(mainDispatcher) {
                if (imageView.tag == key) {
                    if (loaded != null) {
                        showBitmap(imageView, fallbackView, loaded)
                    } else {
                        showFallback(imageView, fallbackView)
                    }
                }
            }
        }
    }

    internal fun fetchContactBitmap(
        context: Context,
        photoUri: String?,
        phoneNumber: String? = null,
        contactId: Long? = null
    ): Bitmap? {
        val resolver = context.contentResolver

        // 1. Direct thumbnail URI (from CallLog.Calls.CACHED_PHOTO_URI or Phone.PHOTO_THUMBNAIL_URI)
        if (!photoUri.isNullOrBlank()) {
            decodeStreamSafely { resolver.openInputStream(Uri.parse(photoUri)) }?.let { return it }
        }

        val hasReadContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasReadContacts) return null

        // 2. Contact ID fallback via ContactsContract.Contacts
        if (contactId != null && contactId > 0L) {
            val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            decodeStreamSafely {
                ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri, false)
            }?.let { return it }
        }

        // 3. PhoneLookup fallback for CallLog entries that do not cache a photo URI yet
        if (!phoneNumber.isNullOrBlank()) {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val resolvedPhotoUri = runCatching {
                resolver.query(
                    lookupUri,
                    arrayOf(
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val thumbIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
                        val thumbStr = if (thumbIdx >= 0) cursor.getString(thumbIdx) else null
                        if (!thumbStr.isNullOrBlank()) {
                            thumbStr
                        } else {
                            val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                            val foundId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                            if (foundId > 0L) {
                                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, foundId).toString()
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                }
            }.getOrNull()

            if (!resolvedPhotoUri.isNullOrBlank()) {
                val parsed = Uri.parse(resolvedPhotoUri)
                decodeStreamSafely {
                    resolver.openInputStream(parsed)
                        ?: ContactsContract.Contacts.openContactPhotoInputStream(resolver, parsed, false)
                }?.let { return it }
            }
        }

        return null
    }

    private inline fun decodeStreamSafely(openStream: () -> java.io.InputStream?): Bitmap? {
        return runCatching {
            openStream()?.use { stream -> BitmapFactory.decodeStream(stream) }
        }.getOrNull()
    }

    private fun resolveCacheKey(photoUri: String?, contactId: Long?, phoneNumber: String?): String? {
        if (!photoUri.isNullOrBlank()) return "uri:$photoUri"
        if (contactId != null && contactId > 0L) return "cid:$contactId"
        val digits = phoneNumber?.filter { it.isDigit() }.orEmpty()
        if (digits.isNotEmpty()) return "num:$digits"
        return null
    }

    private fun showBitmap(imageView: ImageView, fallbackView: View, bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        fallbackView.visibility = View.INVISIBLE
    }

    private fun showFallback(imageView: ImageView, fallbackView: View) {
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        fallbackView.visibility = View.VISIBLE
    }

    @VisibleForTesting
    internal fun clearCache() {
        bitmapCache.evictAll()
        missingKeys.clear()
    }
}
