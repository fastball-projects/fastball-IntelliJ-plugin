package dev.fastball.intellij.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicReference


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/13
 */
class FastballJsonViewEditor(
    project: Project,
    file: VirtualFile,
    editorProvider: TextEditorProvider,
    private val tabName: String
) : PsiAwareTextEditorImpl(project, file, editorProvider) {
    override fun getName() = tabName
}

class FastballJsonViewEditorProvider : TextEditorProvider() {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension != JAVA_FILE_EXT) {
            return false
        }
        return getViewFile(project, file) != null
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val viewFile = getViewFile(project, file) ?: throw IllegalStateException()
        val result = AtomicReference<FileEditor>()
        ApplicationManager.getApplication().invokeAndWait {
            result.set(FastballJsonViewEditor(project, viewFile, this, VIEW_JSON_TAB_NAME))
        }
        return result.get() ?: throw IllegalStateException("Editor could not be created")
    }

    override fun getEditorTypeId(): String = FastballJsonViewEditorProvider::class.java.simpleName
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}