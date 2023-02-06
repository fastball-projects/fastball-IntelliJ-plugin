package dev.fastball.intellij.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.service.execution.NotSupportedException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PathUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.*
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.HttpClients
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/12
 */
interface FastballWebService {
    val port: Int
    val project: Project
    val fileMapper: HashMap<String, VirtualFile>

    companion object {
        fun getInstance(project: Project): FastballWebService {
            return project.getService(FastballWebService::class.java)
        }
    }
}

typealias Test = Array<String>

val b = Test(1) { "" };

class FastballWebServiceImpl(override val project: Project) : FastballWebService {
    override val port = findFreePort()
    override val fileMapper = hashMapOf<String, VirtualFile>()

    init {
        val app = ApplicationManager.getApplication()
        app.runReadAction {
            FilenameIndex.getAllFilesByExt(project, VIEW_FILE_EXT, GlobalSearchScope.everythingScope(project))
                .forEach { file ->
                    fromJson(file.contentsToByteArray()).get(CLASS_NAME_PARAM)?.asText()?.let { className ->
                        fileMapper[className] = file
                    }
                }
        }

        val handlers = listOf(
            StaticResourceHandler(),
            LoadAssetsHandler(this),
            LoadViewHandler(this),
            SaveViewHandler(this),
            ProxyHandler()
        )

        val server = HttpServer.create(InetSocketAddress(port), 256)
        handlers.forEach {
            server.createContext(it.contextPath, it)
        }
        server.executor = Executors.newFixedThreadPool(64)
        server.start()
    }
}

private class HttpExecutor : Executor {
    override fun execute(task: Runnable) {
        task.run()
    }
}

class StaticResourceHandler : AutoOutputWebHandler {
    override val contextPath = CONTEXT_PATH
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val staticResource = FastballWebService::class.java.getResourceAsStream(exchange.requestURI.path)
            ?: throw IllegalArgumentException("Static file [${exchange.requestURI.path}] not found")
        return staticResource.readAllBytes()
    }
}

class LoadAssetsHandler(private val webService: FastballWebService) : AutoOutputWebHandler {
    private val yaml = Yaml()
    override val contextPath = ASSETS_API_PATH
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val app = ApplicationManager.getApplication()
        var maps = listOf<MutableMap<String, Any>>()
        app.runReadAction {
            maps = FilenameIndex.getVirtualFilesByName(
                FASTBALL_MATERIAL_FILE_NAME,
                GlobalSearchScope.everythingScope(webService.project)
            ).map { file -> yaml.load(file.inputStream) }
            maps.forEach {
                if (it[ASSET_META_URL] == null) {
                    it[ASSET_META_URL] = buildUnPkgUrl(
                        it[ASSET_NPM_PACKAGE] as String,
                        it[ASSET_NPM_VERSION] as String
                    ) + "/build/lowcode/meta.js"
                }
                if (it[ASSET_COMPONENT_URLS] == null) {
                    it[ASSET_COMPONENT_URLS] = listOf(
                        buildUnPkgUrl(
                            it[ASSET_NPM_PACKAGE] as String,
                            it[ASSET_NPM_VERSION] as String
                        ) + "/build/lowcode/view.js",
                        buildUnPkgUrl(
                            it[ASSET_NPM_PACKAGE] as String,
                            it[ASSET_NPM_VERSION] as String
                        ) + "/build/lowcode/view.css"
                    )
                }
            }
        }
        return toJson(maps).toByteArray()
    }
}

class LoadViewHandler(private val webService: FastballWebService) : AutoOutputWebHandler {
    override val contextPath = LOAD_VIEW_API_PATH
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val query = queryStringToMap(exchange.requestURI.query)
        val viewFile = webService.fileMapper[query[CLASS_NAME_PARAM]] ?: return byteArrayOf()
        return viewFile.contentsToByteArray()
    }
}

class SaveViewHandler(private val webService: FastballWebService) : AutoOutputWebHandler {
    override val contextPath = SAVE_VIEW_API_PATH
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val query = queryStringToMap(exchange.requestURI.query)
        val className = query[CLASS_NAME_PARAM] ?: return byteArrayOf()
        val viewFile: VirtualFile = webService.fileMapper[className] ?: return byteArrayOf()
        val viewContent = inputStreamToCustomizedPrettyJson(exchange.requestBody)
        WriteCommandAction.runWriteCommandAction(webService.project) {
            writeCustomizedViewFile(viewFile, className, viewContent.toByteArray())
        }
        return viewFile.contentsToByteArray()
    }

    private fun writeCustomizedViewFile(
        viewFile: VirtualFile,
        className: String,
        content: ByteArray
    ) {
        val module = ProjectFileIndex.getInstance(webService.project).getModuleForFile(viewFile, false)
            ?: throw RuntimeException("File $viewFile module not found.")

        val javaRelativePath = className.replace('.', '/')
        val isCustomizedFile = ModuleRootManager.getInstance(module).getSourceRoots(JavaResourceRootType.RESOURCE)
            .any { viewFile.path.startsWith(it.path) }
        if (isCustomizedFile) {
            viewFile.setBinaryContent(content)
            return
        }
        val relativePath = "$FASTBALL_VIEW_DIR_NAME/$javaRelativePath.$VIEW_FILE_EXT"
        val resourceDir =
            ModuleRootManager.getInstance(module).getSourceRoots(JavaResourceRootType.RESOURCE).firstOrNull()
                ?: throw RuntimeException("Module [$module] resource dir not found.")
        resourceDir.createAndWriteFile(relativePath, content)
    }

    private fun VirtualFile.createAndWriteFile(relativePath: String, data: ByteArray): VirtualFile {
        return try {
            WriteAction.computeAndWait<VirtualFile, IOException> {
                var parent = this
                for (name in StringUtil.tokenize(
                    PathUtil.getParentPath(
                        relativePath
                    ), "/"
                )) {
                    var child = parent.findChild(name!!)
                    if (child == null || !child.isValid) {
                        child =
                            parent.createChildDirectory(SaveViewHandler::class.java, name)
                    }
                    parent = child
                }
                parent.children // need this to ensure that fileCreated event is fired
                val name = PathUtil.getFileName(relativePath)
                val manager = FileDocumentManager.getInstance()
                var file = parent.findChild(name)
                if (file == null) {
                    file = parent.createChildData(SaveViewHandler::class.java, name)
                } else {
                    val document = manager.getCachedDocument(file)
                    if (document != null) manager.saveDocument(document) // save changes to prevent possible conflicts
                }
                file.setBinaryContent(data)
                manager.reloadFiles(file) // update the document now, otherwise MemoryDiskConflictResolver will do it later at unexpected moment of time
                file
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }
}

class ProxyHandler : WebHandler {
    override val contextPath = PROXY_PATH
    override fun handle(exchange: HttpExchange) {
        try {
            val proxy = exchange.requestHeaders.getFirst(PROXY_HEADER)
                ?: throw IllegalArgumentException("Request header [$PROXY_HEADER] not found")
            val path = exchange.requestURI.path.substring(contextPath.length)
            val proxyTarget = proxy + path
            val request = when (exchange.requestMethod) {
                "GET" -> HttpGet(proxyTarget)
                "POST" -> HttpPost(proxyTarget)
                "PUT" -> HttpPut(proxyTarget)
                "PATCH" -> HttpPatch(proxyTarget)
                "DELETE" -> HttpDelete(proxyTarget)
                "HEAD" -> HttpHead(proxyTarget)
                "TRACE" -> HttpTrace(proxyTarget)
                "OPTIONS" -> HttpOptions(proxyTarget)
                else -> throw NotSupportedException("Proxy not support method[" + exchange.requestMethod + "]")
            }
            if (request is HttpEntityEnclosingRequestBase) {
                val httpEntity: HttpEntity = InputStreamEntity(exchange.requestBody)
                request.entity = httpEntity
            }
            exchange.requestHeaders.entries.forEach { (header, headerValues): Map.Entry<String, List<String>> ->
                for (value in headerValues) {
                    if (CONTENT_LENGTH_HEADER.equals(header, ignoreCase = true)) {
                        continue
                    }
                    request.addHeader(header, value)
                }
            }

            val httpclient = HttpClients.createDefault()
            val response: HttpResponse = httpclient.execute(request)
            for (header in response.allHeaders) {
                exchange.responseHeaders.add(header.name, header.value)
            }
            val responseEntity = response.entity
            exchange.sendResponseHeaders(response.statusLine.statusCode, 0)
            responseEntity.content.use {
                val bytes = it.readAllBytes()
                exchange.responseBody.use { resp ->
                    resp.write(bytes)
                }
            }
        } catch (throwable: Throwable) {
            val bytes = throwable.stackTraceToString().toByteArray()
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { resp ->
                resp.write(bytes)
            }
        }
    }
}

interface WebHandler : HttpHandler {
    val contextPath: String
}

interface AutoOutputWebHandler : WebHandler {
    override fun handle(exchange: HttpExchange) {
        try {
            val responseBody = handlerRequest(exchange)
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.write(responseBody)
            exchange.responseBody.flush()
            exchange.responseBody.close()
        } catch (throwable: Throwable) {
            val error = throwable.message + "\n" + throwable.stackTraceToString()
            val bytes = error.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    fun handlerRequest(exchange: HttpExchange): ByteArray
}

