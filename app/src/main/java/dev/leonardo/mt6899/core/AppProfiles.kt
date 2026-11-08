package dev.leonardo.mt6899.core

/**
 * Regras "app -> perfil", guardadas numa única preferência como "pacote|perfil" separados por vírgula.
 * Nomes de perfil nunca têm "|" nem "," (ver [Profile.cleanName]), então o formato é inequívoco.
 */
object AppRules {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun isPackage(s: String): Boolean = PKG.matches(s)

    fun decode(raw: String?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (entry in raw.orEmpty().split(',')) {
            val i = entry.indexOf('|')
            if (i <= 0) continue
            val pkg = entry.substring(0, i).trim()
            val profile = entry.substring(i + 1).trim()
            if (isPackage(pkg) && profile.isNotEmpty()) m[pkg] = profile
        }
        return m
    }

    fun encode(rules: Map<String, String>): String =
        rules.entries.joinToString(",") { "${it.key}|${Profile.cleanName(it.value)}" }
}
