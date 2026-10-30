package dev.metaclaude.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.metaclaude.MetaClaudeService
import dev.metaclaude.cli.ClaudeCli
import dev.metaclaude.compat.IntegrationMode
import dev.metaclaude.compat.OfficialPluginDetector
import dev.metaclaude.compat.OfficialState
import javax.swing.JLabel

/** Settings > Tools > Clawed UI. */
class ClaudeUiConfigurable : BoundConfigurable("Clawed UI") {
    private val settings get() = ClaudeUiSettings.instance

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(
                "Unofficial plugin, provided \"as is\" without warranty. Claude Code acts on your files within the permissions " +
                    "you approve: review changes and keep backups. The authors are not liable for data loss or other damage " +
                    "(see DISCLAIMER.md in the source repository).",
            )
        }
        group("Connection") {
            row("Integration mode:") {
                comboBox(IntegrationMode.entries)
                    .bindItem({ settings.integrationMode }, { settings.integrationMode = it ?: IntegrationMode.BUILTIN })
            }
            row { comment(officialStatus()) }

            lateinit var pathField: Cell<JBTextField>
            lateinit var result: Cell<JLabel>
            row("Claude CLI:") {
                pathField = textField()
                    .columns(COLUMNS_LARGE)
                    .bindText({ settings.state.claudePath ?: "" }, { settings.state.claudePath = it.trim().ifEmpty { null } })
                    .comment("Leave empty to find claude on PATH (claude.exe, claude.cmd) or in ~/.local/bin.")
                button("Test") { runCheck(pathField.component.text, result) }
            }
            row { result = label("") }
        }
        group("Context Sent to Claude") {
            row {
                checkBox("Send selected text").bindSelected(settings.state::attachSelection)
                    .comment("Off: Claude sees which file and line you're on, not the text. Alt+K still works.")
            }
            row {
                checkBox("Send the open file").bindSelected(settings.state::attachOpenFile)
                    .comment("Off: the open file is only sent together with a selection.")
            }
            row {
                checkBox("Respect .gitignore").bindSelected(settings.state::respectGitIgnore)
                    .comment("On: text from git-ignored files is never sent. Excluded and out-of-project files are never sent.")
            }
        }
    }

    override fun apply() {
        super.apply()
        MetaClaudeService.applySettingsToOpenProjects() // a mode change starts or stops the bridge right away
    }

    private fun officialStatus(): String = when (OfficialPluginDetector.state()) {
        OfficialState.NOT_INSTALLED -> "Official Claude Code plugin: not installed. Official and Auto act as Built-in."
        OfficialState.DISABLED -> "Official Claude Code plugin: installed but disabled. Official and Auto act as Built-in."
        OfficialState.ENABLED -> "Official Claude Code plugin: installed and enabled."
    }

    private fun runCheck(path: String, result: Cell<JLabel>) {
        result.component.text = "Checking..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val check = ClaudeCli.check(path)
            // ModalityState.any(): the settings dialog is modal, a plain invokeLater would wait until it closes.
            ApplicationManager.getApplication().invokeLater(
                { result.component.text = (if (check.ok) "OK: " else "Problem: ") + check.message },
                ModalityState.any(),
            )
        }
    }
}
