package dev.leonardo.mt6899.core

// ---------- Shell ----------

data class ShellResult(val out: String, val err: String, val code: Int) {
    val ok: Boolean get() = code == 0
}

interface Shell {
    fun exec(cmd: String): ShellResult
}

/** Armazenamento chave/valor simples (SharedPreferences no app, mapa nos testes). */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Aspas simples seguras para shell. */
fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

// ---------- Nós ajustáveis ----------

enum class Risk { SAFE, CAUTION, DANGER }
enum class Kind { INT, CHOICE, TEXT }

data class NodeSpec(
    val id: String,
    val label: String,
    val path: String,
    val group: String,
    val kind: Kind = Kind.INT,
    val risk: Risk = Risk.SAFE,
    val hint: String = "",
    val choices: List<String> = emptyList()
)

// ---------- Hardware detectado ----------

data class CpuPolicy(
    val id: Int,
    val cpus: String,
    val capacity: Int?,
    val freqs: List<Long>,          // kHz, crescente
    val governors: List<String>,
    val minHw: Long,
    val maxHw: Long
) {
    val label: String get() = "Cluster $id · CPU ${cpuRange(cpus)}"
}

/** "0 1 2 3 6" -> "0-3,6" (agrupa só CPUs consecutivas; antes "0 2" virava "0-2"). */
fun cpuRange(cpus: String): String {
    val l = cpus.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.distinct().sorted()
    if (l.isEmpty()) return "?"
    val parts = ArrayList<String>()
    var start = l[0]
    var prev = l[0]
    for (n in l.drop(1) + Int.MIN_VALUE) { // Int.MIN_VALUE: sentinela que descarrega o último grupo
        if (n == prev + 1) {
            prev = n
            continue
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        start = n
        prev = n
    }
    return parts.joinToString(",")
}

data class GpuOpp(val idx: Int, val kHz: Long, val volt: Long)

data class GpuLimit(
    val id: Int,
    val name: String,
    val priority: Int,
    val ceiling: Int,
    val floor: Int,
    val cEnable: Boolean,
    val fEnable: Boolean
)

data class DevfreqInfo(val path: String, val freqs: List<Long>, val governors: List<String>)

data class SocInfo(
    val marketName: String,
    val device: String,
    val socModel: String,
    val kernel: String,
    val gpuName: String,
    val policies: List<CpuPolicy>,
    val gpuOpps: List<GpuOpp>,           // idx 0 = maior frequência
    val ddr: DevfreqInfo?,
    val ufs: DevfreqInfo?,
    val gpuDevfreq: DevfreqInfo?,
    val zones: Map<String, Int>,         // tipo -> número da thermal_zone
    val blockDevs: List<String>,
    val nodes: List<NodeSpec>            // só os que existem neste aparelho
) {
    val gpuMaxKHz: Long get() = gpuOpps.firstOrNull()?.kHz ?: 0L
    val gpuMinKHz: Long get() = gpuOpps.lastOrNull()?.kHz ?: 0L
    fun policy(id: Int): CpuPolicy? = policies.firstOrNull { it.id == id }
    fun node(id: String): NodeSpec? = nodes.firstOrNull { it.id == id }
}

// ---------- Estado ao vivo ----------

data class CpuLive(val cur: Long, val min: Long, val max: Long, val gov: String)

data class Live(
    val ts: Long,
    val cpu: Map<Int, CpuLive>,
    val gpuIdx: Int?,
    val gpuKHz: Long?,
    val gpuUtil: Int?,
    val gpuUpIdx: Int?,
    val gpuBoostIdx: Int?,
    val ppmCeilIdx: Int?,
    val ppmCeilLimiter: Int?,
    val ppmFloorIdx: Int?,
    val ppmFloorLimiter: Int?,
    val limits: List<GpuLimit>,
    val temps: Map<String, Float>,
    val battPct: Int?,
    val battTempC: Float?,
    val flags: Map<String, String>
)

data class WriteResult(
    val path: String,
    val wanted: String,
    val got: String,
    val ok: Boolean,
    val note: String = ""
) {
    fun line(): String {
        val short = path.removePrefix("/sys/").removePrefix("/proc/")
        return if (ok) "OK   $short = $got" else "FALHA $short (pedido=$wanted, lido=$got) $note".trim()
    }
}

// ---------- Perfis ----------

/**
 * Chaves de [values]:
 *  cpuN.gov / cpuN.max / cpuN.min   (kHz; 0 = limite do hardware)
 *  gpu.max_khz / gpu.min_khz        (0 = sem limite)
 *  gpu.limit.<NOME>                 ("off" desliga o teto daquele limitador)
 *  display.hz                       (30/60/90/120)
 *  thermal.pause                    ("1" pausa mi_thermald/thermald)
 *  node:<id>                        (qualquer NodeSpec)
 */
data class Profile(
    val name: String,
    val values: LinkedHashMap<String, String>,
    val builtin: Boolean = false
) {
    companion object {
        fun encode(list: List<Profile>): String =
            list.filter { !it.builtin }.joinToString("\n") { p ->
                "P|" + clean(p.name) + "|" + p.values.entries.joinToString(",") { clean(it.key) + "=" + clean(it.value) }
            }

        fun decode(text: String?): List<Profile> {
            if (text.isNullOrBlank()) return emptyList()
            return text.lines().mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size < 3 || parts[0] != "P") return@mapNotNull null
                val map = LinkedHashMap<String, String>()
                parts[2].split(",").forEach { kv ->
                    val i = kv.indexOf('=')
                    if (i > 0) map[kv.substring(0, i)] = kv.substring(i + 1)
                }
                Profile(parts[1], map, false)
            }
        }

        private fun clean(s: String) = s.replace("|", "/").replace(",", ";").replace("\n", " ")

        /** Nome como ele volta depois de salvar e reler (encode/decode), sem espaços nas pontas. */
        fun cleanName(s: String): String = clean(s).trim()
    }
}
