package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.ContactsRepository
import com.example.data.firebase.CallerDirectoryProfile
import com.example.data.firebase.RealtimeDatabaseSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Verified social / messaging platform presence discovered via Android's synced
 * ContactsContract account types (WhatsApp, Telegram, Signal, Messenger, Viber, Truecaller)
 * or PulseDial's Cloud Caller Directory. Never performs unauthorized private scraping.
 */
data class SocialPlatformMatch(
    val platform: String,
    val profileName: String = "",
    val handleOrAccount: String = "",
    val verifiedOnDevice: Boolean = true
)

data class CallerIdentityInfo(
    val displayName: String,
    val formattedNumber: String,
    val location: String,
    val isUnknownNumber: Boolean,
    val sourceLabel: String,
    val city: String = "",
    val state: String = "",
    val postalCode: String = "",
    val country: String = "",
    val countryCode: String = "",
    val carrier: String = "",
    val lineType: String = "",
    val socialProfiles: List<SocialPlatformMatch> = emptyList(),
    val geo: CallerGeoInfo = CallerGeoInfo()
) {
    fun socialBadgesSummary(): String {
        return socialProfiles
            .map { it.platform.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" • ")
    }

    fun structuredLocationLine(): String {
        return geo.formattedStructuredLine()
    }
}

/**
 * Deterministic Multi-Source Caller Identification & Structured Geographic Resolver.
 *
 * Eliminates all random/modulo location guessing and resolves:
 * 1. Real Name Priority: Saved Contacts -> Synced Messenger/Social Accounts (WhatsApp,
 *    WhatsApp Business, Telegram, Signal, Messenger, Viber, Truecaller) -> PulseDial Cloud
 *    Directory -> Carrier CNAP -> Call Log Cached Name -> Verified National Helplines.
 * 2. Geographic Priority: Contact StructuredPostal Address -> Verified Cloud Directory ->
 *    Official Deterministic Numbering Plan (TRAI/DoT 4-digit Indian mobile & STD codes,
 *    US/Canada NANPA area codes, UK/UAE/AU & ITU-T E.164 international codes) with City,
 *    State, ZIP/PIN Code, Country, and Carrier.
 */
object CallerIdResolver {

    const val UNKNOWN_LOCATION = "Location Unavailable"

    const val PLATFORM_WHATSAPP = "whatsapp"
    const val PLATFORM_TELEGRAM = "telegram"
    const val PLATFORM_FACEBOOK = "facebook"
    const val PLATFORM_INSTAGRAM = "instagram"
    const val PLATFORM_GOOGLE = "google"

    private data class DeviceContactEnrichment(
        val socialProfiles: List<SocialPlatformMatch> = emptyList(),
        val postalGeo: CallerGeoInfo? = null
    )

    private val localVerifiedProfiles = ConcurrentHashMap<String, CallerDirectoryProfile>()

    fun cacheVerifiedCallerProfile(profile: CallerDirectoryProfile) {
        if (profile.phoneKey.isNotBlank() && profile.displayName.isNotBlank()) {
            localVerifiedProfiles[profile.phoneKey] = profile
        }
    }

    fun getCachedVerifiedCallerProfile(rawNumber: String): CallerDirectoryProfile? {
        val key = PhoneGeoDatabase.toE164Digits(rawNumber)
        if (key.isBlank()) return null
        return localVerifiedProfiles[key]
    }

    fun resolveGeo(rawNumber: String): CallerGeoInfo {
        return PhoneGeoDatabase.resolveGeo(rawNumber)
    }

    fun resolveLocation(rawNumber: String): String {
        return PhoneGeoDatabase.resolveGeo(rawNumber).formattedSummary()
    }

    fun resolveInstant(
        rawNumber: String,
        savedContactName: String? = null,
        telecomDisplayName: String? = null,
        cachedCallLogName: String? = null,
        socialProfiles: List<SocialPlatformMatch> = emptyList(),
        cloudProfile: CallerDirectoryProfile? = null,
        postalOverride: CallerGeoInfo? = null
    ): CallerIdentityInfo {
        val cleanDisplayNumber = rawNumber.trim()
        val effectiveCloud = cloudProfile ?: getCachedVerifiedCallerProfile(cleanDisplayNumber)
        val baseGeo = PhoneGeoDatabase.resolveGeo(cleanDisplayNumber)
        val mergedGeo = mergeGeoInfo(
            base = baseGeo,
            cloud = effectiveCloud,
            postal = postalOverride
        )
        val location = mergedGeo.formattedSummary()
        val helplineName = resolveKnownHelplineOrServiceName(cleanDisplayNumber)

        val mergedSocials = mergeSocialProfiles(
            deviceSocials = socialProfiles,
            cloudProfile = effectiveCloud
        )

        val cleanSaved = sanitizeName(savedContactName, cleanDisplayNumber)
        val socialNameMatch = mergedSocials.firstOrNull {
            sanitizeName(it.profileName, cleanDisplayNumber) != null
        }
        val cleanSocialName = socialNameMatch?.let { sanitizeName(it.profileName, cleanDisplayNumber) }
        val cleanCloudName = sanitizeName(effectiveCloud?.displayName, cleanDisplayNumber)
        val cleanTelecom = sanitizeName(telecomDisplayName, cleanDisplayNumber)
        val cleanCached = sanitizeName(cachedCallLogName, cleanDisplayNumber)

        val (resolvedName, isUnknown, sourceLabel) = when {
            cleanSaved != null -> Triple(cleanSaved, false, "Saved Contact")
            cleanSocialName != null -> Triple(
                cleanSocialName,
                false,
                "${socialNameMatch!!.platform} Profile"
            )
            cleanCloudName != null -> Triple(
                cleanCloudName,
                false,
                effectiveCloud?.sourceLabel?.ifBlank { "PulseDial Cloud Directory" }
                    ?: "PulseDial Cloud Directory"
            )
            cleanTelecom != null -> Triple(cleanTelecom, true, "Caller ID (CNAP)")
            cleanCached != null -> Triple(cleanCached, true, "Call Log Caller ID")
            helplineName != null -> Triple(helplineName, true, "Verified Helpline Directory")
            else -> {
                val unknownTitle = if (cleanDisplayNumber.isBlank()) {
                    "Private / Hidden Number"
                } else {
                    "Unknown Caller"
                }
                val source = when {
                    mergedGeo.isExactPrefixMatch -> "Verified Numbering Plan (${mergedGeo.country.ifBlank { "Telecom" }})"
                    mergedGeo.hasLocation -> "Country & Network Identified"
                    else -> "Unknown Number"
                }
                Triple(unknownTitle, true, source)
            }
        }

        return CallerIdentityInfo(
            displayName = resolvedName,
            formattedNumber = cleanDisplayNumber.ifBlank { "Private Number" },
            location = location,
            isUnknownNumber = isUnknown,
            sourceLabel = sourceLabel,
            city = mergedGeo.city,
            state = mergedGeo.state,
            postalCode = mergedGeo.postalCode,
            country = mergedGeo.country,
            countryCode = mergedGeo.countryCode,
            carrier = mergedGeo.carrier,
            lineType = mergedGeo.lineType,
            socialProfiles = mergedSocials,
            geo = mergedGeo
        )
    }

    suspend fun resolveWithDeviceAsync(
        context: Context,
        rawNumber: String,
        telecomDisplayName: String? = null
    ): CallerIdentityInfo = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val repo = ContactsRepository(appCtx)
        val savedName = runCatching { repo.lookupCallerName(rawNumber) }.getOrNull()
        val deviceEnrichment = runCatching {
            lookupSyncedSocialAccountsAndAddress(appCtx, rawNumber)
        }.getOrDefault(DeviceContactEnrichment())

        val cachedLocal = getCachedVerifiedCallerProfile(rawNumber)
        val cloudProfile = cachedLocal ?: runCatching {
            RealtimeDatabaseSync.lookupCallerProfileByPhone(appCtx, rawNumber)
        }.getOrNull()?.also { found ->
            cacheVerifiedCallerProfile(found)
        }

        val cachedCallLogName = if (savedName.isNullOrBlank() &&
            deviceEnrichment.socialProfiles.none { it.profileName.isNotBlank() } &&
            cloudProfile == null
        ) {
            lookupRecentCallLogName(appCtx, rawNumber)
        } else {
            null
        }

        resolveInstant(
            rawNumber = rawNumber,
            savedContactName = savedName,
            telecomDisplayName = telecomDisplayName,
            cachedCallLogName = cachedCallLogName,
            socialProfiles = deviceEnrichment.socialProfiles,
            cloudProfile = cloudProfile,
            postalOverride = deviceEnrichment.postalGeo
        )
    }

    /**
     * Queries Android's ContactsContract (strictly within the already-granted READ_CONTACTS
     * permission) to discover:
     * 1. Synced social/messaging account rows (WhatsApp, WhatsApp Business, Telegram, Signal,
     *    Messenger, Viber, Truecaller) associated with matching Contact IDs — providing real
     *    profile display names and verified platform badges.
     * 2. Saved StructuredPostal address fields (City, State/Region, ZIP/Postal Code, Country)
     *    which take highest accuracy precedence over prefix-based telecom geolocation.
     */
    private fun lookupSyncedSocialAccountsAndAddress(
        context: Context,
        rawNumber: String
    ): DeviceContactEnrichment {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return DeviceContactEnrichment()
        }
        val cleanNumber = rawNumber.trim()
        if (cleanNumber.filter { it.isDigit() }.length < 5) return DeviceContactEnrichment()

        val contactIds = mutableSetOf<Long>()
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(cleanNumber)
        )
        runCatching {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                while (cursor.moveToNext() && contactIds.size < 5) {
                    if (idIdx >= 0) {
                        val cid = cursor.getLong(idIdx)
                        if (cid > 0L) contactIds.add(cid)
                    }
                }
            }
        }
        if (contactIds.isEmpty()) return DeviceContactEnrichment()

        val socialMap = LinkedHashMap<String, SocialPlatformMatch>()
        var postalGeo: CallerGeoInfo? = null

        val placeholders = contactIds.joinToString(",") { "?" }
        val selectionArgs = contactIds.map { it.toString() }.toTypedArray()

        // 1. Discover synced Messenger / Social accounts linked to this contact
        runCatching {
            val dataProjection = arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA3,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                dataProjection,
                "${ContactsContract.Data.CONTACT_ID} IN ($placeholders)",
                selectionArgs,
                null
            )?.use { cursor ->
                val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val displayIdx = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val data1Idx = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                val data3Idx = cursor.getColumnIndex(ContactsContract.Data.DATA3)
                val accTypeIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)

                while (cursor.moveToNext()) {
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                    val accType = if (accTypeIdx >= 0) cursor.getString(accTypeIdx).orEmpty() else ""
                    val displayName = if (displayIdx >= 0) cursor.getString(displayIdx)?.trim().orEmpty() else ""
                    val data1 = if (data1Idx >= 0) cursor.getString(data1Idx)?.trim().orEmpty() else ""
                    val data3 = if (data3Idx >= 0) cursor.getString(data3Idx)?.trim().orEmpty() else ""

                    val platform = identifySocialPlatform(accType, mime)
                    if (platform != null && !socialMap.containsKey(platform)) {
                        val handle = data3.ifBlank { data1 }
                        socialMap[platform] = SocialPlatformMatch(
                            platform = platform,
                            profileName = displayName,
                            handleOrAccount = handle,
                            verifiedOnDevice = true
                        )
                    }
                }
            }
        }

        // 2. Query StructuredPostal for exact City, State, ZIP/PIN, Country when saved in Contacts
        runCatching {
            val postalProjection = arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                postalProjection,
                "${ContactsContract.Data.CONTACT_ID} IN ($placeholders)",
                selectionArgs,
                null
            )?.use { cursor ->
                val cityIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.CITY)
                val regionIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.REGION)
                val zipIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE)
                val countryIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY)

                while (cursor.moveToNext()) {
                    val city = if (cityIdx >= 0) cursor.getString(cityIdx)?.trim().orEmpty() else ""
                    val region = if (regionIdx >= 0) cursor.getString(regionIdx)?.trim().orEmpty() else ""
                    val zip = if (zipIdx >= 0) cursor.getString(zipIdx)?.trim().orEmpty() else ""
                    val country = if (countryIdx >= 0) cursor.getString(countryIdx)?.trim().orEmpty() else ""
                    if (city.isNotEmpty() || region.isNotEmpty() || zip.isNotEmpty() || country.isNotEmpty()) {
                        postalGeo = CallerGeoInfo(
                            city = city,
                            state = region,
                            postalCode = zip,
                            country = country,
                            isExactPrefixMatch = true
                        )
                        break
                    }
                }
            }
        }

        return DeviceContactEnrichment(
            socialProfiles = socialMap.values.toList(),
            postalGeo = postalGeo
        )
    }

    internal fun identifySocialPlatform(accountType: String, mimeType: String): String? {
        val acc = accountType.lowercase()
        val mime = mimeType.lowercase()
        return when {
            acc.contains("whatsapp.w4b") || mime.contains("whatsapp.w4b") -> "WhatsApp Business"
            acc.contains("whatsapp") || mime.contains("whatsapp") -> "WhatsApp"
            acc.contains("telegram") || mime.contains("telegram") -> "Telegram"
            acc.contains("thoughtcrime.securesms") || acc.contains("signal") || mime.contains("signal") -> "Signal"
            acc.contains("facebook.orca") || acc.contains("facebook") || mime.contains("facebook") -> "Facebook / Messenger"
            acc.contains("instagram") || mime.contains("instagram") -> "Instagram"
            acc.contains("viber") || mime.contains("viber") -> "Viber"
            acc.contains("truecaller") || mime.contains("truecaller") -> "Truecaller"
            acc.contains("linkedin") || mime.contains("linkedin") -> "LinkedIn"
            acc.contains("duo") || mime.contains("tachyon") -> "Google Meet"
            else -> null
        }
    }

    /**
     * Builds Play-Policy-compliant one-tap verification URLs so the user can check a phone number
     * directly on WhatsApp, Telegram, Facebook, Instagram, or Google Search.
     */
    fun buildSocialLookupUrl(platform: String, rawNumber: String): String {
        val e164 = PhoneGeoDatabase.toE164Digits(rawNumber)
        val cleanRaw = rawNumber.trim()
        val displayQuery = if (e164.isNotEmpty()) "+$e164" else cleanRaw
        return when (platform.lowercase()) {
            PLATFORM_WHATSAPP -> if (e164.isNotEmpty()) "https://wa.me/$e164" else "https://wa.me/"
            PLATFORM_TELEGRAM -> if (e164.isNotEmpty()) "https://t.me/+$e164" else "https://t.me/"
            PLATFORM_FACEBOOK -> "https://www.facebook.com/search/top/?q=${Uri.encode(displayQuery)}"
            PLATFORM_INSTAGRAM -> "https://www.google.com/search?q=${Uri.encode("\"$displayQuery\" site:instagram.com")}"
            else -> "https://www.google.com/search?q=${Uri.encode("\"$displayQuery\" OR \"$cleanRaw\"")}"
        }
    }

    private fun mergeGeoInfo(
        base: CallerGeoInfo,
        cloud: CallerDirectoryProfile?,
        postal: CallerGeoInfo?
    ): CallerGeoInfo {
        val city = postal?.city?.takeIf { it.isNotBlank() }
            ?: cloud?.city?.takeIf { it.isNotBlank() }
            ?: base.city
        val state = postal?.state?.takeIf { it.isNotBlank() }
            ?: cloud?.state?.takeIf { it.isNotBlank() }
            ?: base.state
        val postalCode = postal?.postalCode?.takeIf { it.isNotBlank() }
            ?: cloud?.postalCode?.takeIf { it.isNotBlank() }
            ?: base.postalCode
        val country = postal?.country?.takeIf { it.isNotBlank() }
            ?: cloud?.country?.takeIf { it.isNotBlank() }
            ?: base.country
        val countryCode = cloud?.countryCode?.takeIf { it.isNotBlank() }
            ?: base.countryCode
        val exact = (postal != null && postal.hasLocation) ||
            (cloud != null && (cloud.city.isNotBlank() || cloud.state.isNotBlank() || cloud.postalCode.isNotBlank())) ||
            base.isExactPrefixMatch

        return base.copy(
            city = city,
            state = state,
            postalCode = postalCode,
            country = country,
            countryCode = countryCode,
            isExactPrefixMatch = exact
        )
    }

    private fun mergeSocialProfiles(
        deviceSocials: List<SocialPlatformMatch>,
        cloudProfile: CallerDirectoryProfile?
    ): List<SocialPlatformMatch> {
        val map = LinkedHashMap<String, SocialPlatformMatch>()
        deviceSocials.forEach { match ->
            val key = match.platform.trim().lowercase()
            if (key.isNotEmpty()) {
                map[key] = match
            }
        }
        cloudProfile?.socialPlatforms?.forEach { platformName ->
            val clean = platformName.trim()
            val key = clean.lowercase()
            if (clean.isNotEmpty() && !map.containsKey(key)) {
                map[key] = SocialPlatformMatch(
                    platform = clean,
                    profileName = cloudProfile.displayName,
                    verifiedOnDevice = false
                )
            }
        }
        return map.values.toList()
    }

    private fun sanitizeName(candidate: String?, rawNumber: String): String? {
        val trimmed = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.equals("Unknown", ignoreCase = true) ||
            trimmed.equals("Unknown Caller", ignoreCase = true) ||
            trimmed.equals("Private Number", ignoreCase = true) ||
            trimmed.equals("Unsaved Number", ignoreCase = true)
        ) {
            return null
        }
        val candDigits = trimmed.filter { it.isDigit() }
        val numDigits = rawNumber.filter { it.isDigit() }
        if (candDigits.isNotEmpty() && candDigits == numDigits && trimmed.none { it.isLetter() }) {
            return null
        }
        return trimmed
    }

    private fun lookupRecentCallLogName(context: Context, rawNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val targetDigits = rawNumber.filter { it.isDigit() }
        if (targetDigits.length < 5) return null
        return runCatching {
            val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME)
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.CACHED_NAME} IS NOT NULL AND ${CallLog.Calls.CACHED_NAME} != ''",
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                var checked = 0
                while (cursor.moveToNext() && checked < 150) {
                    checked++
                    val num = cursor.getString(numIdx).orEmpty().filter { it.isDigit() }
                    val cached = cursor.getString(nameIdx)?.trim().orEmpty()
                    if (cached.isNotEmpty() && ContactsRepository.numbersMatch(targetDigits, num)) {
                        return@use cached
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun resolveKnownHelplineOrServiceName(rawNumber: String): String? {
        val digits = rawNumber.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return when {
            digits == "112" -> "National Emergency (112)"
            digits == "100" -> "Police Emergency (100)"
            digits == "101" -> "Fire Emergency (101)"
            digits == "102" || digits == "108" -> "Ambulance Helpline ($digits)"
            digits == "1091" -> "Women Helpline (1091)"
            digits == "1098" -> "Childline Helpline (1098)"
            digits == "1930" -> "Cyber Crime Helpline (1930)"
            digits == "139" -> "Indian Railways Enquiry (139)"
            digits == "198" -> "Telecom Customer Care (198)"
            digits == "1909" -> "DND Preference Care (1909)"
            digits.startsWith("1800") || digits.startsWith("911800") -> "Toll-Free Support Line"
            digits.startsWith("1860") || digits.startsWith("911860") -> "Business Support Line"
            else -> null
        }
    }
}
