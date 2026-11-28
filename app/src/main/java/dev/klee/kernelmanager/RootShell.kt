package dev.klee.kernelmanager

import android.os.Process
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class CommandResult(val code: Int, val stdout: List<String>, val stderr: List<String>) {
    val success: Boolean get() = code == 0
}

class RootShell {
    companion object {
        init {
            try {
                Shell.enableVerboseLogging = false
            } catch (_: Throwable) {
            }
        }

        private val lock = Any()

        @Volatile
        private var persistentShell: Shell? = null

        @Volatile
        private var directSuPath: String? = null

        @Volatile
        var lastDiagnostic: String = ""
            private set

        private val suCandidates = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su"
        )

        private fun isUid0(lines: List<String>): Boolean =
            lines.any { it.contains("uid=0") }

        private fun execDirect(suBin: String, command: String, timeoutSec: Long = 30L): CommandResult {
            return try {
                val proc = ProcessBuilder(suBin, "-c", command).start()
                val outLines = mutableListOf<String>()
                val errLines = mutableListOf<String>()
                val outThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                            br.forEachLine { outLines.add(it) }
                        }
                    } catch (_: Throwable) {
                    }
                }
                val errThread = Thread {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                            br.forEachLine { errLines.add(it) }
                        }
                    } catch (_: Throwable) {
                    }
                }
                outThread.start()
                errThread.start()
                val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return CommandResult(124, outLines, errLines + "timeout")
                }
                outThread.join(1000)
                errThread.join(1000)
                CommandResult(proc.exitValue(), outLines, errLines)
            } catch (t: Throwable) {
                CommandResult(127, emptyList(), listOf(t.message ?: t.javaClass.simpleName))
            }
        }

        private fun tryBuildLibsu(flags: Int): Pair<Shell?, String> {
            return try {
                val builder = Shell.Builder.create().setTimeout(10)
                if (flags != 0) builder.setFlags(flags)
                val sh = builder.build()
                if (!sh.isAlive) {
                    try { sh.close() } catch (_: Throwable) {}
                    return null to "not_alive"
                }
                val out = ArrayList<String>()
                val err = ArrayList<String>()
                val idRes = sh.newJob().add("id").to(out, err).exec()
                if (idRes.code == 0 && isUid0(out)) {
                    sh to "ok(uid=0)"
                } else {
                    val info = (out + err).joinToString(" ").take(60)
                    try { sh.close() } catch (_: Throwable) {}
                    null to "code=${idRes.code}($info)"
                }
            } catch (t: Throwable) {
                null to "ex:${t.message?.take(50) ?: t.javaClass.simpleName}"
            }
        }

        private fun ensureBackend(): Boolean = synchronized(lock) {
            // 1. Reuse existing live libsu root shell
            val cur = persistentShell
            if (cur != null) {
                if (cur.isAlive) return true
                try { cur.close() } catch (_: Throwable) {}
                persistentShell = null
            }

            // 2. Reuse verified direct su binary
            val dsu = directSuPath
            if (dsu != null) {
                val check = execDirect(dsu, "id", 5L)
                if (check.success && isUid0(check.stdout)) return true
                directSuPath = null
            }

            val diag = mutableListOf("appUid=${Process.myUid()}")

            // Tier 1: libsu with FLAG_MOUNT_MASTER (isolated from global Shell.getShell() cache)
            val (mmShell, mmInfo) = tryBuildLibsu(Shell.FLAG_MOUNT_MASTER)
            diag.add("libsu_mm=$mmInfo")
            if (mmShell != null) {
                persistentShell = mmShell
                lastDiagnostic = diag.joinToString(" | ")
                return true
            }

            // Tier 2: libsu plain root (without --mount-master)
            val (plainShell, plainInfo) = tryBuildLibsu(0)
            diag.add("libsu=$plainInfo")
            if (plainShell != null) {
                persistentShell = plainShell
                lastDiagnostic = diag.joinToString(" | ")
                return true
            }

            // Tier 3: direct ProcessBuilder su fallback (bypasses libsu completely)
            for (cand in suCandidates) {
                val res = execDirect(cand, "id", 5L)
                if (res.success && isUid0(res.stdout)) {
                    directSuPath = cand
                    diag.add("direct($cand)=ok")
                    lastDiagnostic = diag.joinToString(" | ")
                    return true
                }
                if (cand == "su" || cand == "/system/bin/su") {
                    val msg = res.stdout.plus(res.stderr).joinToString(" ").take(50)
                    diag.add("direct($cand)=c${res.code}:$msg")
                }
            }

            lastDiagnostic = diag.joinToString(" | ")
            return false
        }

        private fun execWithBackend(command: String): CommandResult {
            if (!ensureBackend()) {
                return CommandResult(1, emptyList(), listOf("Root indisponível ($lastDiagnostic)"))
            }
            val sh = persistentShell
            if (sh != null && sh.isAlive) {
                return try {
                    val out = ArrayList<String>()
                    val err = ArrayList<String>()
                    val r = sh.newJob().add(command).to(out, err).exec()
                    CommandResult(r.code, out, err)
                } catch (t: Throwable) {
                    synchronized(lock) {
                        try { sh.close() } catch (_: Throwable) {}
                        persistentShell = null
                    }
                    // Retry once via direct su if persistent shell died mid-command
                    val fallback = directSuPath ?: "su"
                    execDirect(fallback, command)
                }
            }
            val dsu = directSuPath ?: return CommandResult(1, emptyList(), listOf("Root indisponível ($lastDiagnostic)"))
            return execDirect(dsu, command)
        }
    }

    fun runReadOnly(command: String): CommandResult {
        require(command.length <= 64_000) { "Comando read-only muito grande (${command.length} chars)." }
        return execWithBackend(command)
    }

    fun runRoot(command: String): CommandResult {
        require(command.length <= 64_000) { "Comando root muito grande (${command.length} chars)." }
        return execWithBackend(command)
    }

    fun rootCheck(): Boolean {
        if (!ensureBackend()) return false
        val r = execWithBackend("id")
        return r.success && isUid0(r.stdout)
    }
}
