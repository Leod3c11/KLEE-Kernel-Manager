package dev.leonardo.mt6899.core

object Parse {
    private val intRe = Regex("-?\\d+")
    private val wsRe = Regex("\\s+")

    fun firstLine(raw: String?): String =
        raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** Primeiro inteiro da primeira linha ("48 260000" -> 48, "[APO_Thr]: 2000 us" -> 2000). */
    fun intOf(raw: String?): Long? = intRe.find(firstLine(raw))?.value?.toLongOrNull()

    fun longs(raw: String?): List<Long> =
        raw?.trim()?.split(wsRe)?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    fun words(raw: String?): List<String> =
        raw?.trim()?.split(wsRe)?.filter { it.isNotEmpty() } ?: emptyList()

    private val oppRe = Regex("\\[(\\d+)\\*?]\\s*freq:\\s*(\\d+),\\s*volt:\\s*(\\d+),\\s*vsram:\\s*(\\d+)")

    fun oppTable(raw: String?): List<GpuOpp> =
        oppRe.findAll(raw ?: "").map {
            GpuOpp(it.groupValues[1].toInt(), it.groupValues[2].toLong(), it.groupValues[3].toLong())
        }.toList()

    private val limitRe = Regex(
        "^\\s*(\\d+)\\s+(\\S+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\d+)\\s+(\\d+)\\s*$",
        RegexOption.MULTILINE
    )

    fun limitTable(raw: String?): List<GpuLimit> =
        limitRe.findAll(raw ?: "").map {
            val g = it.groupValues
            GpuLimit(g[1].toInt(), g[2], g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6] != "0", g[7] != "0")
        }.toList()

    private val oppIdxRe = Regex("\\[GPU\\s+OPP]\\s+Index:\\s*(-?\\d+)")
    private val ceilRe = Regex("\\[PPM Ceiling]\\s+LimitIndex:\\s*(-?\\d+),\\s*Limiter:\\s*(-?\\d+)")
    private val floorRe = Regex("\\[PPM Floor]\\s+LimitIndex:\\s*(-?\\d+),\\s*Limiter:\\s*(-?\\d+)")

    fun gpuOppIndex(raw: String?): Int? = oppIdxRe.find(raw ?: "")?.groupValues?.get(1)?.toIntOrNull()
    fun ppmCeiling(raw: String?): Pair<Int, Int>? = pair(ceilRe.find(raw ?: ""))
    fun ppmFloor(raw: String?): Pair<Int, Int>? = pair(floorRe.find(raw ?: ""))

    private fun pair(m: MatchResult?): Pair<Int, Int>? {
        m ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return a to b
    }

    /** "[none] mq-deadline kyber" -> ("none", [none, mq-deadline, kyber]) */
    fun scheduler(raw: String?): Pair<String, List<String>> {
        val ws = words(raw)
        val active = ws.firstOrNull { it.startsWith("[") }?.trim('[', ']') ?: ""
        return active to ws.map { it.trim('[', ']') }
    }

    /** "48 260000" -> (48, 260000) */
    fun gedCurrent(raw: String?): Pair<Int, Long>? {
        val l = longs(firstLine(raw))
        if (l.size < 2) return null
        return l[0].toInt() to l[1]
    }

    /** Verifica se o valor lido corresponde ao pedido (numérico, texto ou estilo "[none]"). */
    fun same(want: String, got: String): Boolean {
        val w = want.trim()
        if (w.isEmpty()) return false
        if (got.contains("[$w]")) return true
        if (firstLine(got) == w) return true
        val wi = w.toLongOrNull()
        val gi = intOf(got)
        return wi != null && gi != null && wi == gi
    }
}
