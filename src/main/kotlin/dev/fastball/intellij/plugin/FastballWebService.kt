package dev.fastball.intellij.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.service.execution.NotSupportedException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.client.methods.*
import org.apache.http.entity.InputStreamEntity
import org.apache.http.impl.client.HttpClients
import org.yaml.snakeyaml.Yaml
import java.io.FileInputStream
import java.net.InetSocketAddress


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/12
 */
interface FastballWebService {
    val port: Int
    val fileMapper: HashMap<String, VirtualFile>

    companion object {
        fun getInstance(project: Project): FastballWebService {
            return project.getService(FastballWebService::class.java)
        }
    }
}

class FastballWebServiceImpl : FastballWebService {
    override val port = findFreePort()
    override val fileMapper = hashMapOf<String, VirtualFile>()

    init {
        val handlers = listOf(
            StaticResourceHandler(),
            LoadAssetsHandler(),
            LoadViewHandler(this),
            SaveViewHandler(this),
            ProxyHandler()
        )
        val server = HttpServer.create(InetSocketAddress(port), 0)
        handlers.forEach {
            server.createContext(it.contextPath, it)
        }
        server.start()
    }

}

class StaticResourceHandler : AutoOutputWebHandler {
    override val contextPath = "/fastball-editor"
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
//            return FileInputStream("/Users/gr/fastball-projects/editor/" + exchange.requestURI.path).readAllBytes()
        val staticResource = FastballWebService::class.java.getResourceAsStream(exchange.requestURI.path)
            ?: throw IllegalArgumentException("Static file [${exchange.requestURI.path}] not found")
        return staticResource.readAllBytes()
    }
}

class LoadAssetsHandler : AutoOutputWebHandler {
    private val yaml = Yaml()
    override val contextPath = "/fastball-editor/api/assets"
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val app = ApplicationManager.getApplication()
        var maps = listOf<Map<String, Any>>()
        app.runReadAction {
            maps = ProjectManager.getInstance().openProjects.flatMap {
                FilenameIndex.getFilesByName(
                    ProjectManager.getInstance().defaultProject,
                    "fastball-material.yml",
                    GlobalSearchScope.projectScope(it)
                ).map { file -> yaml.load(file.text) }
            }
        }
        return toJson(maps).toByteArray()
    }
}

class LoadViewHandler(private val webService: FastballWebService) : AutoOutputWebHandler {
    override val contextPath = "/fastball-editor/api/load-view"
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val query = queryStringToMap(exchange.requestURI.query)
        val viewFile = webService.fileMapper[query["className"]] ?: return byteArrayOf()
        return viewFile.contentsToByteArray()
    }
}

class SaveViewHandler(private val webService: FastballWebService) : AutoOutputWebHandler {
    override val contextPath = "/fastball-editor/api/save-view"
    override fun handlerRequest(exchange: HttpExchange): ByteArray {
        val query = queryStringToMap(exchange.requestURI.query)
        val viewFile: VirtualFile = webService.fileMapper[query["className"]] ?: return byteArrayOf()
        val viewContent = inputStreamToPrettyJson(exchange.requestBody)
        WriteCommandAction.runWriteCommandAction(ProjectManager.getInstance().defaultProject) {
            viewFile.setBinaryContent(viewContent.toByteArray())
        }
        return viewFile.contentsToByteArray()
    }
}

class ProxyHandler : WebHandler {
    override val contextPath = "/fastball-editor/proxy"
    override fun handle(exchange: HttpExchange) {
        try {
            val proxy = exchange.requestHeaders.getFirst("Fastball-Proxy")
                ?: throw IllegalArgumentException("Request header [Fastball-Proxy] not found")
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
                else -> throw NotSupportedException("proxy not support method[" + exchange.requestMethod + "]")
            }
            if (request is HttpEntityEnclosingRequestBase) {
                val httpEntity: HttpEntity = InputStreamEntity(exchange.requestBody)
                request.entity = httpEntity
            }
            exchange.requestHeaders.entries.forEach { (header, headerValues): Map.Entry<String, List<String>> ->
                for (value in headerValues) {
                    if ("Content-length".equals(header, ignoreCase = true)) {
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

