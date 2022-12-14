package dev.fastball.intellij.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.yaml.snakeyaml.Yaml
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
            LoadViewHandler(fileMapper),
            SaveViewHandler(fileMapper)
        )
        val server = HttpServer.create(InetSocketAddress(port), 0)
        handlers.forEach {
            server.createContext(it.contextPath, it)
        }
        server.start()
    }

    class StaticResourceHandler : AutoOutputWebHandler {
        override val contextPath: String = "/fastball-editor"
        override fun handlerRequest(exchange: HttpExchange): ByteArray {
//            return FileInputStream("/Users/gr/fastball-projects/editor/" + exchange.requestURI.path).readAllBytes()
            return FastballWebService::class.java.getResourceAsStream(exchange.requestURI.path)!!.readAllBytes()
        }
    }

    class LoadAssetsHandler : AutoOutputWebHandler {
        private val yaml = Yaml()
        override val contextPath: String = "/fastball-editor/api/assets"
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

    class LoadViewHandler(private val fileMapper: Map<String, VirtualFile>) : AutoOutputWebHandler {
        override val contextPath: String = "/fastball-editor/api/load-view"
        override fun handlerRequest(exchange: HttpExchange): ByteArray {
            val query = queryStringToMap(exchange.requestURI.query)
            val viewFile: VirtualFile = fileMapper[query["className"]] ?: return byteArrayOf()
            return viewFile.contentsToByteArray()
        }
    }

    class SaveViewHandler(private val fileMapper: Map<String, VirtualFile>) : AutoOutputWebHandler {
        override val contextPath: String = "/fastball-editor/api/save-view"
        override fun handlerRequest(exchange: HttpExchange): ByteArray {
            val query = queryStringToMap(exchange.requestURI.query)
            val viewFile: VirtualFile = fileMapper[query["className"]] ?: return byteArrayOf()
            val viewContent = inputStreamToPrettyJson(exchange.requestBody)
            WriteCommandAction.runWriteCommandAction(ProjectManager.getInstance().defaultProject) {
                viewFile.setBinaryContent(viewContent.toByteArray())
            }
            return viewFile.contentsToByteArray()
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

