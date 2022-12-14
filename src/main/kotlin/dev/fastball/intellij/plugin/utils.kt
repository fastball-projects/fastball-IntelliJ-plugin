package dev.fastball.intellij.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.InputStream
import java.net.ServerSocket


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/12
 */
private val objectMapper = ObjectMapper()

fun buildEditorUrl(port: Int, className: String) = buildProxyUrl(port, className, "index")
fun buildPreviewUrl(port: Int, className: String) = buildProxyUrl(port, className, "preview")

private fun buildProxyUrl(port: Int, className: String, viewType: String): String {
    var url = "http://localhost:$port/fastball-editor/$viewType.html?className=$className"
    FastballSettingsState.instance.let {
        if (it.proxyEnabled) {
            url += "&proxyTarget=${it.proxyTarget}"
        }
    }
    return url
}

fun inputStreamToPrettyJson(input: InputStream): String = objectMapper.readTree(input).toPrettyString()

fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)

fun fromJson(json: ByteArray): JsonNode = objectMapper.readTree(json)
fun <T> fromJson(json: String, clazz: Class<T>): T = objectMapper.readValue(json, clazz)
fun <T> fromJson(json: ByteArray, clazz: Class<T>): T = objectMapper.readValue(json, clazz)

fun queryStringToMap(queryString: String) = queryString
    .split("&").map { it.split("=") }.filter { it.size == 2 }.associate { it[0] to it[1] }

fun findFreePort(): Int {
    var port: Int
    ServerSocket(0).use { socket ->
        socket.reuseAddress = true
        port = socket.localPort
    }
    if (port > 0) {
        return port
    }
    throw RuntimeException("Could not find a free port")
}

fun getViewFile(project: Project, file: VirtualFile): VirtualFile? {
    val module = ModuleUtil.findModuleForFile(file, project) ?: return null
    val javaRelativePath = ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE)
        .find { file.path.startsWith(it.path) }
        ?.let { "${file.parent.path.substring(it.path.length)}/${file.nameWithoutExtension}" } ?: return null
    return ModuleRootManager.getInstance(module).getSourceRoots(JavaResourceRootType.RESOURCE)
        .firstNotNullOfOrNull { it.findFileByRelativePath("FASTBALL-INF/$javaRelativePath.fbv.json") }
}