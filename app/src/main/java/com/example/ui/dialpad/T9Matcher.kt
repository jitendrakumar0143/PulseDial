package com.example.ui.dialpad

import com.example.data.ContactEntry

object T9Matcher {

    fun nameToT9Digits(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name.uppercase()) {
            when (ch) {
                'A', 'B', 'C' -> sb.append('2')
                'D', 'E', 'F' -> sb.append('3')
                'G', 'H', 'I' -> sb.append('4')
                'J', 'K', 'L' -> sb.append('5')
                'M', 'N', 'O' -> sb.append('6')
                'P', 'Q', 'R', 'S' -> sb.append('7')
                'T', 'U', 'V' -> sb.append('8')
                'W', 'X', 'Y', 'Z' -> sb.append('9')
                in '0'..'9' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun matchContacts(query: String, contacts: List<ContactEntry>, maxResults: Int = 8): List<ContactEntry> {
        val digits = query.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()

        return contacts.filter { contact ->
            val normalizedNumber = contact.number.filter { it.isDigit() }
            val t9Name = nameToT9Digits(contact.name)
            normalizedNumber.contains(digits) || t9Name.contains(digits)
        }.take(maxResults)
    }
}
