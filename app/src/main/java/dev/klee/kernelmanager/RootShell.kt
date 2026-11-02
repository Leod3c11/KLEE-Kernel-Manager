package dev.klee.kernelmanager

import com.topjohnwu.superuser.Shell

data class CommandResult(val code: Int, val stdout: List<String>, val stderr: List<String>) {
    val success: Boolean get() = code == 0
}

class RootShell {
    fun runReadOnly(command: String): CommandResult {
        require(command.length <= 12_000)
        val result = Shell.cmd(command).exec()
        return CommandResult(result.code, result.out, result.err)
    }

    fun rootCheck(): Boolean {
        val result = runReadOnly("id")
        return result.success && result.stdout.any { it.contains("uid=0") }
    }
}
