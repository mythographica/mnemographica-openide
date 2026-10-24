package ru.mythographica.mnemographica.webview

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.util.Locale
import javax.swing.UIManager

/**
 * `:root { --vscode-*: ... }` from the current IDE look and feel and editor scheme. The variable set is every
 * `--vscode-*` name referenced by `mnemographica/media/webview.css`, `webview.js` and the three panel templates
 * (`src/webview/panel.ts`, `strategyPanel.ts`, `appChannelPanel.ts`), plus the font sizes VS Code always defines.
 */
object ThemeCss {
    fun isDark(): Boolean = !JBColor.isBright()

    fun css(): String = variables().entries.joinToString("\n", ":root {\n", "\n}") { (name, value) -> "  --vscode-$name: $value;" }

    fun variables(): Map<String, String> {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val labelFont = UIUtil.getLabelFont()
        val foreground = UIUtil.getLabelForeground()
        val editorBackground = scheme.defaultBackground
        val border = JBColor.border()
        val buttonBackground = JBUI.CurrentTheme.Button.defaultButtonColorStart()
        val tooltipBorder = ui("ToolTip.borderColor") ?: border
        val warning = scheme.getAttributes(CodeInsightColors.WARNINGS_ATTRIBUTES)?.effectColor ?: JBColor(0xBE9117, 0xD9A343)
        return linkedMapOf(
            "font-family" to fontFamily(labelFont.family, "system-ui, sans-serif"),
            "font-size" to "${labelFont.size}px",
            "foreground" to css(foreground),
            "descriptionForeground" to css(NamedColorUtil.getInactiveTextColor()),
            "errorForeground" to css(NamedColorUtil.getErrorForeground()),
            "errorBackground" to css(JBUI.CurrentTheme.NotificationError.backgroundColor()),
            "focusBorder" to css(JBUI.CurrentTheme.Focus.focusColor()),
            "editor-background" to css(editorBackground),
            "editor-foreground" to css(scheme.defaultForeground),
            "editor-font-family" to fontFamily(scheme.editorFontName, "monospace"),
            "editor-font-size" to "${scheme.editorFontSize}px",
            "editorWarning-foreground" to css(warning),
            "editorHoverWidget-background" to css(UIUtil.getToolTipBackground()),
            "editorHoverWidget-foreground" to css(UIUtil.getToolTipForeground()),
            "editorHoverWidget-border" to css(tooltipBorder),
            "panel-border" to css(border),
            "button-background" to css(buttonBackground),
            "button-foreground" to css(ui("Button.default.foreground") ?: Color.WHITE),
            "button-hoverBackground" to css(if (isDark()) ColorUtil.brighter(buttonBackground, 1) else ColorUtil.darker(buttonBackground, 1)),
            "dropdown-background" to css(ui("ComboBox.nonEditableBackground") ?: ui("ComboBox.background") ?: UIUtil.getPanelBackground()),
            "dropdown-foreground" to css(ui("ComboBox.foreground") ?: foreground),
            "dropdown-border" to css(ui("Component.borderColor") ?: border),
            "input-background" to css(UIUtil.getTextFieldBackground()),
            "input-foreground" to css(UIUtil.getTextFieldForeground()),
            "input-border" to css(ui("Component.borderColor") ?: border),
            "titleBar-activeBackground" to css(ui("MainToolbar.background") ?: UIUtil.getPanelBackground()),
            "titleBar-activeForeground" to css(ui("MainToolbar.foreground") ?: foreground),
        )
    }

    private fun ui(key: String): Color? = UIManager.getColor(key)

    private fun fontFamily(name: String, fallback: String): String = "\"${name.replace("\"", "")}\", $fallback"

    fun css(color: Color): String =
        if (color.alpha == 255) {
            "#%02x%02x%02x".format(color.red, color.green, color.blue)
        } else {
            "rgba(${color.red}, ${color.green}, ${color.blue}, ${"%.3f".format(Locale.ROOT, color.alpha / 255.0)})"
        }
}
