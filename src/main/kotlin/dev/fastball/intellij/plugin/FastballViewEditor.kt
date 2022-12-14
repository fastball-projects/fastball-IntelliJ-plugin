package dev.fastball.intellij.plugin

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.ui.jcef.JBCefBrowser
import java.beans.PropertyChangeListener


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/12
 */
class FastballPreviewEditor(
    file: VirtualFile, port: Int, className: String
) : FastballBrowserEditor("http://localhost:$port/fastball-editor/preview.html?className=$className", file) {
    override fun getName() = "Preview"
}

class FastballPreviewEditorProvider : FastballBrowserEditorProvider() {
    override fun createEditor(file: VirtualFile, port: Int, className: String) =
        FastballPreviewEditor(file, port, className)

    override fun getEditorTypeId() = "FastballPreviewEditorProvider"
}

class FastballViewEditor(
    file: VirtualFile, port: Int, className: String
) : FastballBrowserEditor("http://localhost:$port/fastball-editor/index.html?className=$className", file) {
    override fun getName() = "UI Editor"
}

class FastballViewEditorProvider : FastballBrowserEditorProvider() {
    override fun createEditor(file: VirtualFile, port: Int, className: String) =
        FastballViewEditor(file, port, className)

    override fun getEditorTypeId() = "FastballViewEditorProvider"
}

abstract class FastballBrowserEditorProvider : FileEditorProvider {
    abstract fun createEditor(file: VirtualFile, port: Int, className: String): FastballBrowserEditor

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension != "java") {
            return false
        }
        return getViewFile(project, file) != null
    }

    override fun createEditor(project: Project, file: VirtualFile): FastballBrowserEditor {
        val psiJavaFile = PsiManager.getInstance(project).findFile(file) as PsiJavaFile
        val className = psiJavaFile.classes[0].qualifiedName ?: throw IllegalStateException()
        val viewFile = getViewFile(project, file) ?: throw IllegalStateException()
        val webService = FastballWebService.getInstance(project)
        webService.fileMapper[className] = viewFile
        return createEditor(viewFile, webService.port, className)
    }

    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

abstract class FastballBrowserEditor(url: String, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val browser = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(true).setUrl(url).createBrowser()

    override fun dispose() {
        Disposer.dispose(browser)
    }

    override fun getComponent() = browser.component

    override fun getPreferredFocusedComponent() = browser.component

    override fun setState(state: FileEditorState) {}

    override fun isModified() = false

    override fun isValid() = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation() = null

    override fun getFile() = file
}