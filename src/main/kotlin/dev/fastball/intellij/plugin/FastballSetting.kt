package dev.fastball.intellij.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.xmlb.XmlSerializerUtil
import javax.swing.JComponent

import javax.swing.JPanel


/**
 *
 * @author gr@fastball.dev
 * @since 2022/12/15
 */

@State(name = "FastballSettingsState", storages = [Storage(FASTBALL_SETTING_FILE_NAME)])
class FastballSettingsState : PersistentStateComponent<FastballSettingsState> {
    var proxyTarget = FASTBALL_SETTING_DEFAULT_PROXY_TARGET
    var proxyEnabled = true

    override fun getState(): FastballSettingsState {
        return this
    }

    override fun loadState(state: FastballSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: FastballSettingsState
            get() = ApplicationManager.getApplication().getService(FastballSettingsState::class.java)
    }
}

class FastballSettingsComponent {
    private val proxyTargetField = JBTextField()
    private val proxyEnabledField = JBCheckBox("Enable api proxy")
    val panel: JPanel =
        FormBuilder.createFormBuilder().addComponent(proxyEnabledField, 1)
            .addLabeledComponent(JBLabel("ProxyUrl: "), proxyTargetField, 1, false)
            .addComponentFillVertically(JPanel(), 0).panel

    val preferredFocusedComponent: JComponent
        get() = proxyTargetField

    var proxyTarget: String
        get() = proxyTargetField.text
        set(newText) {
            proxyTargetField.text = newText
        }
    var proxyEnabled: Boolean
        get() = proxyEnabledField.isSelected
        set(newStatus) {
            proxyEnabledField.isSelected = newStatus
        }
}

class FastballSettingsConfigurable : Configurable {

    lateinit var component: FastballSettingsComponent

    override fun getPreferredFocusedComponent() = component.preferredFocusedComponent

    override fun createComponent(): JComponent {
        component = FastballSettingsComponent()
        return component.panel
    }

    override fun isModified(): Boolean {
        val settings = FastballSettingsState.instance
        return (component.proxyEnabled != settings.proxyEnabled) or (component.proxyTarget != settings.proxyTarget)
    }

    override fun apply() {
        val settings = FastballSettingsState.instance
        settings.proxyEnabled = component.proxyEnabled
        settings.proxyTarget = component.proxyTarget
    }

    override fun reset() {
        val settings = FastballSettingsState.instance
        component.proxyEnabled = settings.proxyEnabled
        component.proxyTarget = settings.proxyTarget
    }

    override fun getDisplayName() = FASTBALL_SETTING_NAME
}