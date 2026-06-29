package com.corecoder.app.core

import com.corecoder.app.core.exec.CommandExecutor
import com.corecoder.app.core.tools.ShellHelper
import com.corecoder.app.data.SkillDao
import com.corecoder.app.data.SkillEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Skill discovery, reading, and persistence.
 *
 * Skills live in ~/corecoder/skills/ on the Ubuntu (proot) filesystem.
 * Each Skill is a folder named after the skill, containing a SKILL.md file.
 */
@Singleton
class SkillManager @Inject constructor(
    private val executor: CommandExecutor,
    private val skillDao: SkillDao
) {

    companion object {
        private const val SKILLS_DIR = "~/corecoder/skills"
        private const val SKILL_FILE = "SKILL.md"
        private const val MAX_DESCRIPTION_LENGTH = 100
    }

    /**
     * Ensure the skills directory exists.
     */
    suspend fun ensureSkillsDir() {
        executor.execute("mkdir -p $SKILLS_DIR")
    }

    /**
     * Scan the skills directory, parse SKILL.md files, and sync to Room DB.
     * New skills are inserted; skills whose folders were deleted are removed.
     */
    suspend fun scanSkills() {
        ensureSkillsDir()

        // List subdirectories in ~/corecoder/skills/
        val listResult = executor.execute("ls -1 $SKILLS_DIR 2>/dev/null")
        if (!listResult.isSuccess || listResult.stdout.isBlank()) {
            // No skills directory or empty — clear all
            skillDao.deleteOrphaned(emptyList())
            return
        }

        val folderNames = listResult.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val foundIds = mutableListOf<String>()

        for (folder in folderNames) {
            // Check if SKILL.md exists in this folder
            val skillFilePath = "$SKILLS_DIR/$folder/$SKILL_FILE"
            val checkResult = executor.execute("test -f '${ShellHelper.quote(skillFilePath)}' && echo exists")
            if (!checkResult.isSuccess || !checkResult.stdout.trim().contains("exists")) continue

            // Read SKILL.md content (first 500 chars is enough for metadata)
            val readResult = executor.execute("head -c 500 '${ShellHelper.quote(skillFilePath)}' | base64")
            if (!readResult.isSuccess) continue

            val content = ShellHelper.decodeB64(readResult.stdout)
            if (content.isBlank()) continue

            val description = extractDescription(content)

            val entity = SkillEntity(
                id = folder,
                name = folder,
                description = description,
                folderPath = "$SKILLS_DIR/$folder",
                enabled = true,  // will be overridden by existing record if present
                createdAt = System.currentTimeMillis()
            )

            // Preserve existing enabled state
            val existing = skillDao.getAllSkillsList().find { it.id == folder }
            val toInsert = if (existing != null) {
                entity.copy(enabled = existing.enabled, createdAt = existing.createdAt)
            } else {
                entity
            }

            skillDao.insert(toInsert)
            foundIds.add(folder)
        }

        // Remove skills that no longer exist on disk
        skillDao.deleteOrphaned(foundIds)
    }

    /**
     * Read the full SKILL.md content for a given skill name.
     */
    suspend fun readSkillContent(skillName: String): String? {
        val skillFilePath = "$SKILLS_DIR/${ShellHelper.quote(skillName)}/$SKILL_FILE"
        val result = executor.execute("base64 < '${ShellHelper.quote(skillFilePath)}'")
        if (!result.isSuccess) return null
        val content = ShellHelper.decodeB64(result.stdout)
        return content.ifBlank { null }
    }

    /**
     * Extract description from SKILL.md content.
     * Uses the first [MAX_DESCRIPTION_LENGTH] characters of non-header, non-empty content.
     */
    private fun extractDescription(content: String): String {
        val lines = content.lines()
        val bodyLines = lines.filter { line ->
            line.isNotBlank() && !line.startsWith("---") && !line.startsWith("#")
        }
        val text = bodyLines.joinToString(" ").trim()
        return if (text.length > MAX_DESCRIPTION_LENGTH) {
            text.take(MAX_DESCRIPTION_LENGTH) + "..."
        } else {
            text
        }
    }
}
