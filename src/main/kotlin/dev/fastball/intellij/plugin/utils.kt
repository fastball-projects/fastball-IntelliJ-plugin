package dev.fastball.intellij.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.PsiFileFactory
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.InputStream
import java.net.BindException
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

fun inputStreamToCustomizedPrettyJson(input: InputStream): String {
    val jsonObject = objectMapper.readTree(input) as ObjectNode
    jsonObject.put("customized", true);
    return jsonObject.toPrettyString()
}

fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)

fun fromJson(json: ByteArray): JsonNode = objectMapper.readTree(json)
fun <T> fromJson(json: String, clazz: Class<T>): T = objectMapper.readValue(json, clazz)
fun <T> fromJson(json: ByteArray, clazz: Class<T>): T = objectMapper.readValue(json, clazz)

fun queryStringToMap(queryString: String) = queryString
    .split("&").map { it.split("=") }.filter { it.size == 2 }.associate { it[0] to it[1] }

fun findFreePort(): Int {
    var port: Int
    try {
        ServerSocket(FastballSettingsState.instance.editorServerPort).use { socket ->
            socket.reuseAddress = true
            port = socket.localPort
        }
    } catch (e: BindException) {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            port = socket.localPort
        }
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
    return getCustomizedViewFile(module, javaRelativePath) ?: getGeneratedViewFile(module, javaRelativePath)
}

fun getCustomizedViewFile(module: com.intellij.openapi.module.Module, javaRelativePath: String) =
    ModuleRootManager.getInstance(module).getSourceRoots(JavaResourceRootType.RESOURCE).firstNotNullOfOrNull {
        it.findFileByRelativePath("$FASTBALL_VIEW_DIR_NAME/$javaRelativePath.$VIEW_FILE_EXT")
    }

fun getGeneratedViewFile(module: com.intellij.openapi.module.Module, javaRelativePath: String) =
    ModuleRootManager.getInstance(module).excludeRoots.firstNotNullOfOrNull {
        it.findFileByRelativePath("$FASTBALL_GENERATE_VIEW_DIR$javaRelativePath.$VIEW_FILE_EXT")
    }

fun buildUnPkgUrl(npmPackage: String, npmVersion: String) = "https://unpkg.com/$npmPackage@$npmVersion"