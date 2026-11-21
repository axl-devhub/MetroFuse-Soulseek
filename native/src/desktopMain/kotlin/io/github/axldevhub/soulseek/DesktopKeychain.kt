package io.github.axldevhub.soulseek

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The system keychain on macOS (`security`) and Linux (`secret-tool`), for desktops where MetroFuse has no encrypted
 * credential store. Passwords go to the tools on stdin, never as arguments, so they do not show in the process list.
 */
object DesktopKeychain {
    private const val SERVICE = "metrofuse-soulseek"
    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val mac = "mac" in os
    private val linux = "linux" in os && File("/usr/bin/secret-tool").canExecute()

    val available: Boolean get() = mac || linux

    fun read(username: String): String? =
        when {
            mac -> run(listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", username, "-w"))
            linux -> run(listOf("/usr/bin/secret-tool", "lookup", "service", SERVICE, "user", username))
            else -> null
        }?.trimEnd('\n')?.takeIf { it.isNotEmpty() }

    fun save(
        username: String,
        password: String,
    ): Boolean =
        when {
            // Interactive mode reads the command from stdin; -X takes the password as hex, which needs no quoting.
            mac -> {
                val hex = password.toByteArray().joinToString("") { "%02x".format(it) }
                run(listOf("/usr/bin/security", "-i"), "add-generic-password -U -s $SERVICE -a ${quote(username)} -X $hex\n") != null
            }

            linux -> {
                run(listOf("/usr/bin/secret-tool", "store", "--label=Soulseek (MetroFuse)", "service", SERVICE, "user", username), password) != null
            }

            else -> {
                false
            }
        }

    fun delete(username: String) {
        when {
            mac -> run(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", username))
            linux -> run(listOf("/usr/bin/secret-tool", "clear", "service", SERVICE, "user", username))
        }
    }

    /** Usernames are quoted for `security -i`'s parser; Soulseek names cannot contain double quotes or newlines. */
    private fun quote(value: String) = "\"" + value.filter { it != '"' && it != '\\' && it != '\n' } + "\""

    private fun run(
        command: List<String>,
        stdin: String? = null,
    ): String? =
        runCatching {
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray()) } }
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            output.takeIf { process.exitValue() == 0 }
        }.getOrNull()
}
