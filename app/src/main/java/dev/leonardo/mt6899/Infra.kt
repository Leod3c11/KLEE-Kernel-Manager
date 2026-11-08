package dev.leonardo.mt6899

import android.content.Context
import dev.leonardo.mt6899.core.Shell
import dev.leonardo.mt6899.core.ShellResult
import dev.leonardo.mt6899.core.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ponte entre o núcleo (sem Android) e o shell root do libsu. */
object RootShell : Shell {
    override fun exec(cmd: String): ShellResult {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        // Subshell: os comandos do Engine usam `exit N` (ex.: nó inexistente = 3). O shell do libsu é
        // persistente e compartilhado; assim um `exit` encerra só o subshell, nunca a sessão root.
        val r = com.topjohnwu.superuser.Shell.cmd("(\n$cmd\n)").to(out, err).exec()
        return ShellResult(out.joinToString("\n"), err.joinToString("\n"), r.code)
    }

    fun hasRoot(): Boolean = com.topjohnwu.superuser.Shell.getShell().isRoot
}

class PrefsStore(ctx: Context) : Store {
    private val sp = ctx.applicationContext.getSharedPreferences("mt6899", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        sp.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = sp.all.keys.toSet()
}

object LogBuffer {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow

    @Synchronized
    fun add(s: String) {
        val l = _flow.value + "${fmt.format(Date())} $s"
        _flow.value = if (l.size > 2000) l.takeLast(2000) else l
    }

    @Synchronized
    fun snapshot(): List<String> = _flow.value.toList()

    @Synchronized
    fun clear() {
        _flow.value = emptyList()
    }
}
