package dev.connectquick.controller.files

/**
 * Turns a file name that arrived over the network into one that is safe to create on this phone.
 * The PC is trusted less than it looks (it may be compromised, or just buggy), so nothing it names is
 * used as a path: directories, traversal, control and bidi-override characters, reserved device names
 * and over-long names are all neutralised, and a name that ends up empty gets a neutral default.
 */
object FileNames {
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    private const val MAX_LEN = 120

    fun safe(raw: String, fallback: String = "archivo"): String {
        // Only the last path segment counts, whatever separator style was used.
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = buildString {
            for (ch in name) {
                val code = ch.code
                val control = code < 0x20 || code == 0x7f || (code in 0x80..0x9f)
                val bidi = code in 0x202a..0x202e || code in 0x2066..0x2069 || code == 0x200e || code == 0x200f
                val forbidden = ch in "<>:\"|?*"
                append(if (control || bidi) "" else if (forbidden) "_" else ch.toString())
            }
        }
        name = name.trim().trimEnd('.', ' ')
        if (name.isEmpty() || name.all { it == '.' }) return fallback
        val stem = name.substringBefore('.')
        if (stem.uppercase() in reserved) name = "_$name"
        if (name.length > MAX_LEN) {
            val ext = name.substringAfterLast('.', "").take(12)
            val keep = MAX_LEN - (if (ext.isEmpty()) 0 else ext.length + 1)
            name = if (ext.isEmpty() || !name.contains('.')) name.take(MAX_LEN) else name.take(keep) + "." + ext
        }
        return name
    }
}
