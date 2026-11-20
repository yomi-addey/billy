package com.example.billy.data

/** A selectable calling code. */
data class Country(val name: String, val dialCode: String, val flag: String)

val countries = listOf(
    Country("United States", "+1", "🇺🇸"),
    Country("Canada", "+1", "🇨🇦"),
    Country("United Kingdom", "+44", "🇬🇧"),
    Country("Nigeria", "+234", "🇳🇬"),
    Country("Ghana", "+233", "🇬🇭"),
    Country("Kenya", "+254", "🇰🇪"),
    Country("South Africa", "+27", "🇿🇦"),
    Country("Ireland", "+353", "🇮🇪"),
    Country("France", "+33", "🇫🇷"),
    Country("Germany", "+49", "🇩🇪"),
    Country("Spain", "+34", "🇪🇸"),
    Country("Italy", "+39", "🇮🇹"),
    Country("Brazil", "+55", "🇧🇷"),
    Country("Mexico", "+52", "🇲🇽"),
    Country("India", "+91", "🇮🇳"),
    Country("China", "+86", "🇨🇳"),
    Country("Japan", "+81", "🇯🇵"),
    Country("Australia", "+61", "🇦🇺"),
    Country("New Zealand", "+64", "🇳🇿"),
    Country("United Arab Emirates", "+971", "🇦🇪"),
)

object PhoneNumbers {
    /** Digits only, with leading zeros (national trunk prefix) removed. */
    fun nationalDigits(input: String): String =
        input.filter { it.isDigit() }.trimStart('0')

    /** E.164 allows at most 15 digits including the country code. */
    fun isValid(dialCode: String, input: String): Boolean {
        val national = nationalDigits(input)
        val ccDigits = dialCode.count { it.isDigit() }
        return when (dialCode) {
            "+1" -> national.length == 10
            else -> national.length >= 4 && national.length + ccDigits <= 15
        }
    }

    /** E.164 formatted number, e.g. +14155550123. Call only when [isValid]. */
    fun toE164(dialCode: String, input: String): String = dialCode + nationalDigits(input)
}
