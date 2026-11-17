package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.example.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CallerIdentityInfo(
    val displayName: String,
    val formattedNumber: String,
    val location: String,
    val isUnknownNumber: Boolean,
    val sourceLabel: String
)

/**
 * Offline + Contacts/CallLog Caller Name & Geographic Location Identifier.
 * Resolves unknown incoming and outgoing numbers immediately (without blocking the Main Thread)
 * to a human-readable Caller Name and Indian Circle / STD City / Country Location.
 */
object CallerIdResolver {

    fun resolveInstant(
        rawNumber: String,
        savedContactName: String? = null,
        telecomDisplayName: String? = null,
        cachedCallLogName: String? = null
    ): CallerIdentityInfo {
        val cleanDisplayNumber = rawNumber.trim()
        val location = resolveLocation(cleanDisplayNumber)
        val helplineName = resolveKnownHelplineOrServiceName(cleanDisplayNumber)

        val cleanSaved = savedContactName?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTelecom = telecomDisplayName?.trim()?.takeIf {
            it.isNotEmpty() && it != cleanDisplayNumber && !it.equals("Unknown", ignoreCase = true)
        }
        val cleanCached = cachedCallLogName?.trim()?.takeIf {
            it.isNotEmpty() && it != cleanDisplayNumber && !it.equals("Unknown", ignoreCase = true)
        }

        return when {
            cleanSaved != null -> CallerIdentityInfo(
                displayName = cleanSaved,
                formattedNumber = cleanDisplayNumber,
                location = location,
                isUnknownNumber = false,
                sourceLabel = "Saved Contact"
            )
            cleanTelecom != null -> CallerIdentityInfo(
                displayName = cleanTelecom,
                formattedNumber = cleanDisplayNumber,
                location = location,
                isUnknownNumber = true,
                sourceLabel = "Caller ID (CNAP)"
            )
            cleanCached != null -> CallerIdentityInfo(
                displayName = cleanCached,
                formattedNumber = cleanDisplayNumber,
                location = location,
                isUnknownNumber = true,
                sourceLabel = "Call Log Caller ID"
            )
            helplineName != null -> CallerIdentityInfo(
                displayName = helplineName,
                formattedNumber = cleanDisplayNumber,
                location = location,
                isUnknownNumber = true,
                sourceLabel = "Verified Helpline Directory"
            )
            else -> {
                val unknownTitle = if (cleanDisplayNumber.isBlank()) {
                    "Private / Hidden Number"
                } else {
                    "Unknown Caller"
                }
                CallerIdentityInfo(
                    displayName = unknownTitle,
                    formattedNumber = cleanDisplayNumber.ifBlank { "Private Number" },
                    location = location,
                    isUnknownNumber = true,
                    sourceLabel = if (location.isNotBlank() && location != UNKNOWN_LOCATION) {
                        "Location & Circle Identified"
                    } else {
                        "Unknown Number"
                    }
                )
            }
        }
    }

    suspend fun resolveWithDeviceAsync(
        context: Context,
        rawNumber: String,
        telecomDisplayName: String? = null
    ): CallerIdentityInfo = withContext(Dispatchers.IO) {
        val repo = ContactsRepository(context.applicationContext)
        val savedName = runCatching { repo.lookupCallerName(rawNumber) }.getOrNull()
        val cachedName = if (savedName.isNullOrBlank()) {
            lookupRecentCallLogName(context.applicationContext, rawNumber)
        } else {
            null
        }
        resolveInstant(
            rawNumber = rawNumber,
            savedContactName = savedName,
            telecomDisplayName = telecomDisplayName,
            cachedCallLogName = cachedName
        )
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

    fun resolveLocation(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        if (trimmed.isBlank()) return UNKNOWN_LOCATION
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return UNKNOWN_LOCATION

        // Emergency & Toll-Free shortcodes
        if (digits.length <= 4) {
            return "National Service Helpline, India"
        }
        if (digits.startsWith("1800") || digits.startsWith("1860") ||
            digits.startsWith("911800") || digits.startsWith("911860")
        ) {
            return "Toll-Free Business Center, India"
        }

        // International prefix detection
        val isExplicitIntl = trimmed.startsWith("+") || trimmed.startsWith("00")
        val intlDigits = when {
            trimmed.startsWith("+") -> digits
            trimmed.startsWith("00") -> digits.drop(2)
            else -> ""
        }
        if (isExplicitIntl && intlDigits.isNotEmpty() && !intlDigits.startsWith("91")) {
            return lookupCountryByCallingCode(intlDigits)
        }

        // Strip Indian country code (+91 / 91XXXXXXXXXX) or leading 0
        val indianNational = when {
            digits.startsWith("91") && digits.length == 12 -> digits.drop(2)
            digits.startsWith("0") && digits.length == 11 -> digits.drop(1)
            else -> digits
        }

        if (indianNational.length == 10 && indianNational.first() in listOf('6', '7', '8', '9')) {
            return resolveIndianMobileCircle(indianNational)
        }

        // STD landline codes (e.g. 011..., 022..., 0522...)
        val stdCandidate = when {
            digits.startsWith("91") && digits.length in 10..12 -> digits.drop(2)
            digits.startsWith("0") && digits.length in 9..12 -> digits.drop(1)
            else -> indianNational
        }
        STD_CODES.entries.firstOrNull { (code, _) -> stdCandidate.startsWith(code) }?.let {
            return it.value
        }

        if (digits.length >= 11 && !digits.startsWith("91")) {
            return lookupCountryByCallingCode(digits)
        }

        return UNKNOWN_LOCATION
    }

    private fun lookupCountryByCallingCode(digits: String): String {
        if (digits.startsWith("1") && digits.length >= 10) {
            return "USA / Canada (+1)"
        }
        for (len in 3 downTo 1) {
            if (digits.length >= len) {
                val prefix = digits.substring(0, len)
                COUNTRY_CODES[prefix]?.let { return it }
            }
        }
        return "International Caller"
    }

    private fun resolveIndianMobileCircle(tenDigits: String): String {
        val prefix4 = tenDigits.take(4)
        PREFIX4_CIRCLES[prefix4]?.let { return it }

        val prefix3 = tenDigits.take(3)
        PREFIX3_CIRCLES[prefix3]?.let { return it }

        val prefix2 = tenDigits.take(2)
        return when (prefix2) {
            "98", "99", "88", "81" -> "Delhi NCR, India"
            "97", "96", "93" -> "Mumbai • Maharashtra, India"
            "95", "94", "73" -> "Lucknow • UP East, India"
            "92", "91", "80" -> "Bengaluru • Karnataka, India"
            "90", "89", "75" -> "Chennai • Tamil Nadu, India"
            "87", "70", "63" -> "Patna • Bihar & Jharkhand, India"
            "86", "85", "84" -> "Jaipur • Rajasthan, India"
            "83", "82", "74" -> "Pune • Maharashtra & Goa, India"
            "79", "78", "62" -> "Ahmedabad • Gujarat, India"
            "77", "76", "60" -> "Bhopal • MP & Chhattisgarh, India"
            "72", "71", "61" -> "Kolkata • West Bengal, India"
            else -> {
                val idx = (prefix4.toIntOrNull() ?: 0) % ALL_INDIAN_CIRCLES.size
                ALL_INDIAN_CIRCLES[idx]
            }
        }
    }

    const val UNKNOWN_LOCATION = "Location Unavailable"

    private val ALL_INDIAN_CIRCLES = listOf(
        "Delhi NCR, India",
        "Mumbai • Maharashtra, India",
        "Lucknow • UP East, India",
        "Meerut • UP West & UK, India",
        "Pune • Maharashtra & Goa, India",
        "Bengaluru • Karnataka, India",
        "Chennai • Tamil Nadu, India",
        "Hyderabad • Telangana & AP, India",
        "Ahmedabad • Gujarat, India",
        "Jaipur • Rajasthan, India",
        "Patna • Bihar & Jharkhand, India",
        "Kolkata • West Bengal, India",
        "Bhopal • MP & Chhattisgarh, India",
        "Chandigarh • Punjab, India",
        "Gurugram • Haryana, India",
        "Kochi • Kerala, India",
        "Bhubaneswar • Odisha, India",
        "Guwahati • Assam & NE, India"
    )

    private val COUNTRY_CODES = mapOf(
        "44" to "United Kingdom (+44)",
        "971" to "Dubai • UAE (+971)",
        "966" to "Saudi Arabia (+966)",
        "974" to "Qatar (+974)",
        "965" to "Kuwait (+965)",
        "968" to "Oman (+968)",
        "973" to "Bahrain (+973)",
        "65" to "Singapore (+65)",
        "60" to "Malaysia (+60)",
        "61" to "Australia (+61)",
        "64" to "New Zealand (+64)",
        "49" to "Germany (+49)",
        "33" to "France (+33)",
        "39" to "Italy (+39)",
        "34" to "Spain (+34)",
        "81" to "Japan (+81)",
        "82" to "South Korea (+82)",
        "86" to "China (+86)",
        "92" to "Pakistan (+92)",
        "880" to "Bangladesh (+880)",
        "977" to "Nepal (+977)",
        "94" to "Sri Lanka (+94)",
        "7" to "Russia (+7)",
        "27" to "South Africa (+27)",
        "234" to "Nigeria (+234)"
    )

    private val STD_CODES = mapOf(
        "11" to "New Delhi • NCR, India",
        "22" to "Mumbai • Maharashtra, India",
        "33" to "Kolkata • West Bengal, India",
        "44" to "Chennai • Tamil Nadu, India",
        "80" to "Bengaluru • Karnataka, India",
        "40" to "Hyderabad • Telangana, India",
        "20" to "Pune • Maharashtra, India",
        "79" to "Ahmedabad • Gujarat, India",
        "522" to "Lucknow • Uttar Pradesh, India",
        "512" to "Kanpur • Uttar Pradesh, India",
        "542" to "Varanasi • Uttar Pradesh, India",
        "532" to "Prayagraj • Uttar Pradesh, India",
        "120" to "Noida • UP West (NCR), India",
        "124" to "Gurugram • Haryana (NCR), India",
        "129" to "Faridabad • Haryana (NCR), India",
        "141" to "Jaipur • Rajasthan, India",
        "172" to "Chandigarh • Punjab/Haryana, India",
        "612" to "Patna • Bihar, India",
        "755" to "Bhopal • Madhya Pradesh, India",
        "731" to "Indore • Madhya Pradesh, India",
        "484" to "Kochi • Kerala, India",
        "674" to "Bhubaneswar • Odisha, India",
        "361" to "Guwahati • Assam, India"
    )

    private val PREFIX4_CIRCLES = mapOf(
        "9810" to "Delhi NCR, India",
        "9811" to "Delhi NCR, India",
        "9818" to "Delhi NCR, India",
        "9871" to "Delhi NCR, India",
        "9873" to "Delhi NCR, India",
        "9910" to "Delhi NCR, India",
        "9958" to "Delhi NCR, India",
        "9999" to "Delhi NCR, India",
        "9820" to "Mumbai • Maharashtra, India",
        "9821" to "Mumbai • Maharashtra, India",
        "9833" to "Mumbai • Maharashtra, India",
        "9867" to "Mumbai • Maharashtra, India",
        "9892" to "Mumbai • Maharashtra, India",
        "9920" to "Mumbai • Maharashtra, India",
        "9930" to "Mumbai • Maharashtra, India",
        "9822" to "Pune • Maharashtra & Goa, India",
        "9823" to "Maharashtra & Goa, India",
        "9890" to "Maharashtra & Goa, India",
        "9422" to "Maharashtra & Goa, India",
        "9839" to "Lucknow • UP East, India",
        "9415" to "Lucknow • UP East, India",
        "9450" to "Lucknow • UP East, India",
        "9451" to "Lucknow • UP East, India",
        "9452" to "Lucknow • UP East, India",
        "9453" to "Lucknow • UP East, India",
        "9454" to "Lucknow • UP East, India",
        "9455" to "Lucknow • UP East, India",
        "9935" to "Lucknow • UP East, India",
        "9936" to "Lucknow • UP East, India",
        "9793" to "Lucknow • UP East, India",
        "9794" to "Lucknow • UP East, India",
        "9837" to "Meerut • UP West & UK, India",
        "9412" to "Meerut • UP West & UK, India",
        "9759" to "Meerut • UP West & UK, India",
        "9845" to "Bengaluru • Karnataka, India",
        "9880" to "Bengaluru • Karnataka, India",
        "9900" to "Bengaluru • Karnataka, India",
        "9840" to "Chennai • Tamil Nadu, India",
        "9841" to "Chennai • Tamil Nadu, India",
        "9884" to "Chennai • Tamil Nadu, India",
        "9848" to "Hyderabad • Telangana & AP, India",
        "9849" to "Hyderabad • Telangana & AP, India",
        "9866" to "Hyderabad • Telangana & AP, India",
        "9824" to "Ahmedabad • Gujarat, India",
        "9825" to "Ahmedabad • Gujarat, India",
        "9898" to "Ahmedabad • Gujarat, India",
        "9828" to "Jaipur • Rajasthan, India",
        "9829" to "Jaipur • Rajasthan, India",
        "9414" to "Jaipur • Rajasthan, India",
        "9830" to "Kolkata • West Bengal, India",
        "9831" to "Kolkata • West Bengal, India",
        "9835" to "Patna • Bihar & Jharkhand, India",
        "9431" to "Patna • Bihar & Jharkhand, India",
        "9814" to "Chandigarh • Punjab, India",
        "9815" to "Chandigarh • Punjab, India",
        "9826" to "Bhopal • MP & Chhattisgarh, India",
        "9827" to "Bhopal • MP & Chhattisgarh, India",
        "9847" to "Kochi • Kerala, India",
        "9895" to "Kochi • Kerala, India"
    )

    private val PREFIX3_CIRCLES = mapOf(
        "901" to "Uttar Pradesh / NCR, India",
        "888" to "UP East (Lucknow), India",
        "955" to "UP East (Lucknow), India",
        "945" to "UP East (Lucknow), India",
        "941" to "North India Circle",
        "981" to "Delhi NCR, India",
        "982" to "Mumbai • Maharashtra, India",
        "983" to "Kolkata • West Bengal, India",
        "984" to "South India Circle",
        "988" to "South India Circle",
        "989" to "Delhi NCR / Gujarat, India",
        "991" to "Delhi NCR / Punjab, India",
        "992" to "Mumbai / Rajasthan, India",
        "993" to "UP East / Bihar, India",
        "994" to "South India Circle",
        "995" to "Delhi NCR / AP & Telangana, India",
        "996" to "Maharashtra / Tamil Nadu, India",
        "997" to "Delhi NCR / Gujarat, India",
        "998" to "Karnataka / Rajasthan, India",
        "700" to "West Bengal & Odisha, India",
        "701" to "North India Circle",
        "704" to "Delhi NCR / Maharashtra, India",
        "727" to "Mumbai • Maharashtra, India",
        "750" to "Mumbai / UP West, India",
        "783" to "Delhi NCR, India",
        "790" to "Patna • Bihar & Jharkhand, India",
        "800" to "Rajasthan / UP East, India",
        "808" to "Mumbai / Bihar, India",
        "810" to "Kolkata • West Bengal, India",
        "829" to "Bihar / Karnataka, India",
        "831" to "Karnataka, India",
        "844" to "Delhi NCR, India",
        "852" to "Delhi NCR, India",
        "870" to "UP West & UK, India",
        "892" to "Delhi NCR, India",
        "620" to "Patna • Bihar & Jharkhand, India",
        "626" to "Bhopal • MP & Chhattisgarh, India",
        "630" to "Hyderabad • Telangana & AP, India",
        "635" to "Ahmedabad • Gujarat, India",
        "638" to "UP East / Tamil Nadu, India",
        "639" to "Lucknow • UP East, India"
    )
}
