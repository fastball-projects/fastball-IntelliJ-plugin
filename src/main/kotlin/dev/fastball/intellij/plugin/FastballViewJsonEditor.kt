package dev.fastball.intellij.plugin

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorImpl
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile


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
        if (file.extension != "java") {
            return false
        }
        return getViewFile(project, file) != null
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val viewFile = getViewFile(project, file) ?: throw IllegalStateException()
        return FastballJsonViewEditor(project, viewFile, this, "ViewJson")
    }

    override fun getEditorTypeId() = "FastballViewJsonEditorProvider"
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}