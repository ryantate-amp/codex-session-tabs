package com.github.ryantateamp.codexsessiontabs

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class CodexSessionTabsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<CodexSessionTabsService>().start()
    }
}
