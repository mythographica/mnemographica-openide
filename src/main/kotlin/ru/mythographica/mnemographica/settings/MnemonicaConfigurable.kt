package ru.mythographica.mnemographica.settings

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import ru.mythographica.mnemographica.sidecar.NodeLocator
import ru.mythographica.mnemographica.sidecar.SidecarService
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings | Tools | Mnemonica: `nodePath` plus one field per `contributes.configuration` property
 * (boolean -> checkbox, number -> spinner, string -> text field, enum -> combo box). Apply pushes `config/changed`.
 */
class MnemonicaConfigurable(private val project: Project) : Configurable {
    private val nodePathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Node.js Executable File"))
    }
    private val editors = linkedMapOf<String, Editor>()
    private var schema: ConfigSchema? = null

    private interface Editor {
        val component: JComponent
        var value: JsonElement?
    }

    override fun getDisplayName(): String = "Mnemonica"

    override fun createComponent(): JComponent {
        schema = SidecarService.getInstance(project).configSchema
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Node.js path:", nodePathField)
            .addComponentToRightColumn(comment("Empty: PATH, then ~/.nvm, then /opt/homebrew and /usr/local. Node ${NodeLocator.MIN_MAJOR}+ is required."))
        val currentSchema = schema
        if (currentSchema == null) {
            builder.addComponent(comment("Extension settings appear here once the Mnemonica sidecar has started."))
        } else {
            currentSchema.title?.let { builder.addSeparator().addComponent(JBLabel(it)) }
            for (prop in currentSchema.properties.values) {
                val editor = editorFor(prop)
                editors[prop.key] = editor
                val label = prop.key.substringAfter('.')
                if (editor.component is JBCheckBox) {
                    builder.addComponent(editor.component)
                } else {
                    builder.addLabeledComponent("$label:", editor.component)
                }
                if (prop.description.isNotBlank()) builder.addComponentToRightColumn(comment(prop.description))
            }
        }
        val panel = builder.addComponentFillVertically(JPanel(), 0).panel
        reset()
        return panel
    }

    private fun comment(text: String): JComponent = JBLabel(text).apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    private fun editorFor(prop: ConfigProperty): Editor = when {
        prop.enumValues.isNotEmpty() -> object : Editor {
            val combo = ComboBox(prop.enumValues.toTypedArray())
            override val component: JComponent = combo
            override var value: JsonElement?
                get() = (combo.selectedItem as? String)?.let(::JsonPrimitive)
                set(v) {
                    combo.selectedItem = v?.takeIf { it.isJsonPrimitive }?.asString
                }
        }
        prop.type == "boolean" -> object : Editor {
            val box = JBCheckBox(prop.key.substringAfter('.'))
            override val component: JComponent = box
            override var value: JsonElement?
                get() = JsonPrimitive(box.isSelected)
                set(v) {
                    box.isSelected = v?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                }
        }
        prop.type == "number" || prop.type == "integer" -> object : Editor {
            val integer = prop.type == "integer" || prop.default?.asString?.contains('.') == false
            val spinner = JSpinner(
                if (integer) SpinnerNumberModel(0, Int.MIN_VALUE, Int.MAX_VALUE, 1) else SpinnerNumberModel(0.0, -1e12, 1e12, 1.0),
            )
            override val component: JComponent = spinner
            override var value: JsonElement?
                get() = JsonPrimitive(spinner.value as Number)
                set(v) {
                    val number = v?.takeIf { it.isJsonPrimitive }?.asNumber ?: 0
                    spinner.value = if (integer) number.toInt() else number.toDouble()
                }
        }
        else -> object : Editor {
            val textField = JBTextField()
            override val component: JComponent = textField
            override var value: JsonElement?
                get() = JsonPrimitive(textField.text)
                set(v) {
                    textField.text = v?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                }
        }
    }

    private fun current(key: String): JsonElement? {
        val settings = MnemonicaSettings.getInstance(project)
        return settings.storedValue(key) ?: schema?.properties?.get(key)?.default
    }

    override fun isModified(): Boolean {
        val settings = MnemonicaSettings.getInstance(project)
        if (nodePathField.text.trim() != settings.nodePath) return true
        return editors.any { (key, editor) -> !sameValue(editor.value, current(key)) }
    }

    override fun apply() {
        val settings = MnemonicaSettings.getInstance(project)
        val nodeChanged = nodePathField.text.trim() != settings.nodePath
        settings.nodePath = nodePathField.text.trim()
        for ((key, editor) in editors) {
            settings.setValue(key, editor.value, schema?.properties?.get(key)?.default)
        }
        val service = SidecarService.getInstance(project)
        if (nodeChanged) service.restart() else service.pushConfig()
    }

    override fun reset() {
        nodePathField.text = MnemonicaSettings.getInstance(project).nodePath
        for ((key, editor) in editors) editor.value = current(key)
    }

    override fun disposeUIResources() {
        editors.clear()
    }

    private fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == b) return true
        val left = a?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
        val right = b?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
        return if (left.isNumber && right.isNumber) left.asDouble == right.asDouble else left.asString == right.asString
    }
}
