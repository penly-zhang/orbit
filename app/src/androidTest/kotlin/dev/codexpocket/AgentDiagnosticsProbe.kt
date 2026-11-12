package dev.codexpocket

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.codexpocket.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString

/** Opt-in diagnostics and a separate shared-mode repair. Credentials stay in the phone's vault. */
internal class AgentDiagnosticsProbe(private val runner: Instrumentation) {
    fun run(profileName: String, threadId: String, enableShared: Boolean = false, commandBase64: String = "") {
        val result = Bundle()
        val output = StringBuilder()
        try { runBlocking {
            val vault = Vault(runner.targetContext)
            val profile = vault.profiles().single { it.name == profileName }
            if (commandBase64.isNotBlank()) {
                val command = java.util.Base64.getDecoder().decode(commandBase64).toString(Charsets.UTF_8)
                SshConnection.connect(profile, vault.credentials(profile.id), "cat", handshake = false).use { ssh ->
                    val response = ssh.execute(command, 65536)
                    output.appendLine("EXIT=${response.code}").appendLine(response.stdout).appendLine(response.stderr)
                }
                return@runBlocking
            }
            val targetThread = if (threadId.startsWith("title:")) {
                val saved = json.decodeFromString<SavedWorkspace>(requireNotNull(vault.get("workspace:v1")))
                saved.hosts.single { it.id == profile.id }.tabs.filter { it.thread.title.contains(threadId.removePrefix("title:"), ignoreCase = true) }.maxBy { it.number }.thread.id
            } else threadId
            if (targetThread.isNotBlank()) output.appendLine("THREAD=$targetThread")
            SshConnection.connect(profile, vault.credentials(profile.id), "cat", handshake = false).use { ssh ->
                val repair = if (enableShared) """
                    agent_home="${'$'}{TRAE_HOME:-${'$'}HOME/.trae}"
                    config="${'$'}agent_home/traecli.toml"
                    if [ -f "${'$'}config" ]; then backup="${'$'}config.orbit-backup-${'$'}(date +%Y%m%d-%H%M%S)"; cp -p "${'$'}config" "${'$'}backup" || exit 1; printf 'CONFIG_BACKUP=%s\n' "${'$'}backup"; fi
                    "${'$'}agent_bin" features enable daemon_auto_start || exit 1
                """.trimIndent() else ""
                val script = """
                    printf 'USER=%s\nHOME=%s\nTRAE_HOME=%s\n' "${'$'}(id -un)" "${'$'}HOME" "${'$'}{TRAE_HOME:-<default>}"
                    agent_bin=${'$'}(command -v traex || true)
                    if [ -z "${'$'}agent_bin" ]; then agent_bin="${'$'}HOME/.local/bin/traex"; fi
                    printf 'BINARY=%s\n' "${'$'}agent_bin"
                    "${'$'}agent_bin" --version
                    "${'$'}agent_bin" app-server daemon version
                    $repair
                    "${'$'}agent_bin" features list | awk '/daemon_auto_start/ {print}'
                    botmux_bin=${'$'}(command -v botmux || true)
                    printf 'BOTMUX=%s\n' "${'$'}botmux_bin"
                    if [ -n "${'$'}botmux_bin" ]; then "${'$'}botmux_bin" --version; fi
                    ps -eo pid,ppid,comm | awk '/traex|traecli/ {print}'
                """.trimIndent()
                val command = ssh.execute("sh -lc " + shellQuote(script.lineSequence().filter(String::isNotBlank).joinToString(";")), 16384)
                output.appendLine(command.stdout).appendLine(command.stderr)
            }
            if (targetThread.isNotBlank()) {
                val repository = CodexEngine(EngineDescriptor("traex", "traex")).open(profile, vault.credentials(profile.id), this)
                try {
                    repository.initialize()
                    try { repository.attachThread(targetThread.removePrefix("traex::")); output.appendLine("ATTACH: success") }
                    catch (error: Exception) {
                        val detail = error.cause?.message ?: error.message.orEmpty()
                        output.appendLine("ATTACH: $detail")
                        val pid = Regex("owner PID (\\d+)").find(detail)?.groupValues?.get(1)
                        if (pid != null) {
                            val script = """
                                import os,json
                                p=$pid
                                allowed={'--no-daemon','--remote','--resume','--session-id','--sock','--cd','-C','resume','app-server','daemon','start','proxy','exec','--listen','stdio://','--stdio','--disable','daemon_auto_start','--enable'}
                                for _ in range(2):
                                    base='/proc/'+str(p)
                                    args=open(base+'/cmdline','rb').read().decode().split(chr(0))
                                    env=dict(x.split('=',1) for x in open(base+'/environ','rb').read().decode().split(chr(0)) if '=' in x)
                                    print(json.dumps({'pid':p,'exe':os.readlink(base+'/exe'),'modeArgs':[x for x in args if x in allowed or x.startswith('features.daemon')],'home':{k:env.get(k) for k in ['HOME','TRAE_HOME','XDG_RUNTIME_DIR']},'wrapperPaths':[x for x in args if x.startswith('/') and ('traex-herdr' in x or 'botmux' in x)]}))
                                    for path in args:
                                        if path.startswith('/') and 'traex-herdr' in path and os.path.isfile(path):
                                            text=open(path).read()
                                            print(json.dumps({'wrapper':path,'forcesEmbeddedMode':'--no-daemon' in text,'mentionsTraeHome':'TRAE_HOME' in text,'mentionsRemote':'--remote' in text}))
                                    lines=open(base+'/status').read().splitlines()
                                    p=int(next(x for x in lines if x.startswith('PPid:')).split()[1])
                            """.trimIndent()
                            val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
                            SshConnection.connect(profile, vault.credentials(profile.id), "cat", handshake = false).use { ssh ->
                                val command = ssh.execute("python3 -c " + shellQuote("import base64;exec(base64.b64decode('$encoded'))"), 8192)
                                output.appendLine(command.stdout).appendLine(command.stderr)
                            }
                        }
                    }
                } finally { repository.close() }
            }
        } } catch (error: Exception) { output.appendLine("DIAGNOSTIC ERROR: ${error.message}") }
        result.putString("stream", output.toString())
        runner.finish(Activity.RESULT_OK, result)
    }
}
