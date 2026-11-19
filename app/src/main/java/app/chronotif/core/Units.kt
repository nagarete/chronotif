// SPDX-License-Identifier: GPL-3.0-or-later

package app.chronotif.core

/**
 * Unit conversion for Search (ADR 0008), offline: "5 km in mi", "30 c to f", "2kg". Without a
 * target, a unit converts to its usual counterpart (km → mi, kg → lb, °C → °F).
 */
object Units {
    enum class Kind { LENGTH, MASS, TEMPERATURE, VOLUME, SPEED, AREA, DATA, TIME }

    /** [factor] converts to the kind's base unit; temperatures convert through [toBase] instead. */
    class Unit(
        val symbol: String,
        val kind: Kind,
        val factor: Double,
        val names: List<String>,
        val counterpart: String? = null,
    )

    class Conversion(val amount: Double, val from: Unit, val result: Double, val to: Unit)

    private fun u(symbol: String, kind: Kind, factor: Double, counterpart: String?, vararg names: String) =
        Unit(symbol, kind, factor, listOf(symbol.lowercase()) + names, counterpart)

    val all: List<Unit> = listOf(
        u("mm", Kind.LENGTH, 0.001, "in", "millimeter", "millimeters", "millimetre", "millimetres"),
        u("cm", Kind.LENGTH, 0.01, "in", "centimeter", "centimeters", "centimetre", "centimetres"),
        u("m", Kind.LENGTH, 1.0, "ft", "meter", "meters", "metre", "metres"),
        u("km", Kind.LENGTH, 1000.0, "mi", "kilometer", "kilometers", "kilometre", "kilometres", "kms"),
        u("in", Kind.LENGTH, 0.0254, "cm", "inch", "inches", "\""),
        u("ft", Kind.LENGTH, 0.3048, "m", "foot", "feet", "'"),
        u("yd", Kind.LENGTH, 0.9144, "m", "yard", "yards"),
        u("mi", Kind.LENGTH, 1609.344, "km", "mile", "miles"),
        u("nmi", Kind.LENGTH, 1852.0, "km", "nautical mile", "nautical miles"),

        u("mg", Kind.MASS, 1e-6, "g", "milligram", "milligrams"),
        u("g", Kind.MASS, 0.001, "oz", "gram", "grams", "gr"),
        u("kg", Kind.MASS, 1.0, "lb", "kilogram", "kilograms", "kilo", "kilos", "kgs"),
        u("t", Kind.MASS, 1000.0, "lb", "tonne", "tonnes", "ton", "tons"),
        u("oz", Kind.MASS, 0.028349523125, "g", "ounce", "ounces"),
        u("lb", Kind.MASS, 0.45359237, "kg", "pound", "pounds", "lbs"),
        u("st", Kind.MASS, 6.35029318, "kg", "stone", "stones"),

        u("°C", Kind.TEMPERATURE, 1.0, "°F", "c", "celsius", "centigrade", "degc", "deg c", "degrees c", "degrees celsius"),
        u("°F", Kind.TEMPERATURE, 1.0, "°C", "f", "fahrenheit", "degf", "deg f", "degrees f", "degrees fahrenheit"),
        u("K", Kind.TEMPERATURE, 1.0, "°C", "kelvin", "kelvins"),

        u("ml", Kind.VOLUME, 0.001, "fl oz", "milliliter", "milliliters", "millilitre", "millilitres"),
        u("cl", Kind.VOLUME, 0.01, "fl oz", "centiliter", "centiliters", "centilitre", "centilitres"),
        u("dl", Kind.VOLUME, 0.1, "cup", "deciliter", "deciliters", "decilitre", "decilitres"),
        u("l", Kind.VOLUME, 1.0, "gal", "liter", "liters", "litre", "litres", "ltr"),
        u("m³", Kind.VOLUME, 1000.0, "gal", "m3", "cubic meter", "cubic meters", "cubic metre", "cubic metres"),
        u("tsp", Kind.VOLUME, 0.00492892159375, "ml", "teaspoon", "teaspoons"),
        u("tbsp", Kind.VOLUME, 0.01478676478125, "ml", "tablespoon", "tablespoons"),
        u("fl oz", Kind.VOLUME, 0.0295735295625, "ml", "floz", "fluid ounce", "fluid ounces"),
        u("cup", Kind.VOLUME, 0.2365882365, "ml", "cups"),
        u("pt", Kind.VOLUME, 0.473176473, "l", "pint", "pints"),
        u("qt", Kind.VOLUME, 0.946352946, "l", "quart", "quarts"),
        u("gal", Kind.VOLUME, 3.785411784, "l", "gallon", "gallons"),

        u("m/s", Kind.SPEED, 1.0, "km/h", "mps", "meters per second", "metres per second"),
        u("km/h", Kind.SPEED, 1 / 3.6, "mph", "kmh", "kph", "kmph", "kilometers per hour", "kilometres per hour"),
        u("mph", Kind.SPEED, 0.44704, "km/h", "miles per hour"),
        u("kn", Kind.SPEED, 1852.0 / 3600, "km/h", "knot", "knots", "kt"),
        u("ft/s", Kind.SPEED, 0.3048, "m/s", "fps", "feet per second"),

        u("cm²", Kind.AREA, 1e-4, "in²", "cm2", "square centimeter", "square centimeters", "sq cm"),
        u("m²", Kind.AREA, 1.0, "ft²", "m2", "sqm", "sq m", "square meter", "square meters", "square metre", "square metres"),
        u("km²", Kind.AREA, 1e6, "mi²", "km2", "sq km", "square kilometer", "square kilometers", "square kilometre", "square kilometres"),
        u("ha", Kind.AREA, 1e4, "acre", "hectare", "hectares"),
        u("in²", Kind.AREA, 0.00064516, "cm²", "in2", "sq in", "square inch", "square inches"),
        u("ft²", Kind.AREA, 0.09290304, "m²", "ft2", "sqft", "sq ft", "square foot", "square feet"),
        u("acre", Kind.AREA, 4046.8564224, "ha", "acres", "ac"),
        u("mi²", Kind.AREA, 2589988.110336, "km²", "mi2", "sq mi", "square mile", "square miles"),

        u("B", Kind.DATA, 1.0, "KB", "byte", "bytes"),
        u("KB", Kind.DATA, 1e3, "B", "kilobyte", "kilobytes"),
        u("MB", Kind.DATA, 1e6, "KB", "megabyte", "megabytes"),
        u("GB", Kind.DATA, 1e9, "MB", "gigabyte", "gigabytes"),
        u("TB", Kind.DATA, 1e12, "GB", "terabyte", "terabytes"),
        u("KiB", Kind.DATA, 1024.0, "KB", "kibibyte", "kibibytes"),
        u("MiB", Kind.DATA, 1048576.0, "MB", "mebibyte", "mebibytes"),
        u("GiB", Kind.DATA, 1073741824.0, "GB", "gibibyte", "gibibytes"),

        u("ms", Kind.TIME, 0.001, "s", "millisecond", "milliseconds"),
        u("s", Kind.TIME, 1.0, "min", "sec", "secs", "second", "seconds"),
        u("min", Kind.TIME, 60.0, "s", "mins", "minute", "minutes"),
        u("h", Kind.TIME, 3600.0, "min", "hr", "hrs", "hour", "hours"),
        u("day", Kind.TIME, 86400.0, "h", "days", "d"),
        u("week", Kind.TIME, 604800.0, "day", "weeks", "wk", "wks"),
        u("year", Kind.TIME, 31557600.0, "day", "years", "yr", "yrs"),
    )

    private val byName: Map<String, Unit> = buildMap {
        for (unit in all) for (n in unit.names) putIfAbsent(n, unit)
    }

    private val CONNECTORS = setOf("to", "in", "into", "as", "=", "->", "→")

    fun unit(name: String): Unit? = byName[name.trim().lowercase().replace(Regex("\\s+"), " ")]

    /** Reads "[number][unit] (to|in|into|as) [unit]", or "[number][unit]" for the usual counterpart. */
    fun convert(text: String, numbers: Numbers): Conversion? {
        val s = text.trim()
        var end = 0
        if (s.startsWith('-')) end++
        while (end < s.length && numbers.isNumberChar(s[end])) end++
        val numberText = s.substring(0, end).trimEnd()
        if (numberText.none(Char::isDigit)) return null
        val negative = numberText.startsWith('-')
        val amount = numbers.parse(numberText.removePrefix("-").trim())?.let { if (negative) -it else it } ?: return null
        val words = s.substring(end).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null

        val whole = unit(words.joinToString(" "))
        if (whole != null) {
            val to = whole.counterpart?.let(::unit) ?: return null
            return Conversion(amount, whole, convert(amount, whole, to), to)
        }
        for (k in 1 until words.size - 1) {
            if (words[k].lowercase() !in CONNECTORS) continue
            val from = unit(words.subList(0, k).joinToString(" ")) ?: continue
            val to = unit(words.subList(k + 1, words.size).joinToString(" ")) ?: continue
            if (from.kind != to.kind) return null
            return Conversion(amount, from, convert(amount, from, to), to)
        }
        return null
    }

    fun convert(amount: Double, from: Unit, to: Unit): Double {
        if (from.kind != Kind.TEMPERATURE) return amount * from.factor / to.factor
        val celsius = when (from.symbol) {
            "°F" -> (amount - 32) * 5 / 9
            "K" -> amount - 273.15
            else -> amount
        }
        return when (to.symbol) {
            "°F" -> celsius * 9 / 5 + 32
            "K" -> celsius + 273.15
            else -> celsius
        }
    }
}
