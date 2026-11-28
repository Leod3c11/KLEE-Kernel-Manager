package dev.klee.kernelmanager

import com.topjohnwu.superuser.Shell

data class CommandResult(val code: Int, val stdout: List<String>, val stderr: List<String>) {
    val success: Boolean get() = code == 0
}

class RootShell {
    companion object {
        init {
            try {
                Shell.enableVerboseLogging = false
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(15)
                )
            } catch (_: Throwable) {
                // Already initialized
            }
        }

        @Volatile
        private var fallbackShell: Shell? = null
    }

    @Synchronized
    private fun acquireRootShell(): Shell? {
        val cached = Shell.getCachedShell()
        if (cached != null) {
            if (cached.isAlive && cached.isRoot) return cached
            try {
                cached.close()
            } catch (_: Throwable) {
            }
        }

        val fb = fallbackShell
        if (fb != null) {
            if (fb.isAlive && fb.isRoot) return fb
            try {
                fb.close()
            } catch (_: Throwable) {
            }
            fallbackShell = null
        }

        val main = try {
            Shell.getShell()
        } catch (_: Throwable) {
            null
        }
        if (main != null && main.isAlive && main.isRoot) {
            return main
        }
        // Never leave a non-root shell cached, so granting KernelSU later takes effect immediately without force-stop
        if (main != null) {
            try {
                main.close()
            } catch (_: Throwable) {
            }
        }

        // Fallback: try plain su without --mount-master
        val plain = try {
            Shell.Builder.create().setTimeout(10).build("su")
        } catch (_: Throwable) {
            null
        }
        if (plain != null && plain.isAlive && plain.isRoot) {
            fallbackShell = plain
            return plain
        }
        if (plain != null) {
            try {
                plain.close()
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun runReadOnly(command: String): CommandResult {
        require(command.length <= 64_000) { "Comando read-only muito grande (${command.length} chars)." }
        val sh = acquireRootShell()
            ?: return CommandResult(1, emptyList(), listOf("Acesso root não concedido no KernelSU."))
        val result = sh.newJob().add(command).exec()
        return CommandResult(result.code, result.out, result.err)
    }

    fun runRoot(command: String): CommandResult {
        require(command.length <= 64_000) { "Comando root muito grande (${command.length} chars)." }
        val sh = acquireRootShell()
            ?: return CommandResult(1, emptyList(), listOf("Acesso root não concedido no KernelSU."))
        val result = sh.newJob().add(command).exec()
        return CommandResult(result.code, result.out, result.err)
    }

    fun rootCheck(): Boolean {
        val sh = acquireRootShell() ?: return false
        val result = sh.newJob().add("id").exec()
        return result.code == 0 && result.out.any { it.contains("uid=0") }
    }
}
