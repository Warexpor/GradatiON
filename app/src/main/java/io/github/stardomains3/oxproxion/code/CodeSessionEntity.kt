package io.github.stardomains3.oxproxion.code

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted Code mode session index (survives process death). Transcript event blobs stay
 * deferred; [lastSeq] is enough for bridge `session/load` resume.
 */
@Entity(
    tableName = "code_session",
    indices = [Index(value = ["hostId"]), Index(value = ["updatedAt"])]
)
data class CodeSessionEntity(
    @PrimaryKey val id: String,
    val hostId: String,
    val harness: String,
    val cwd: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** [PermissionMode.id] */
    val mode: String,
    val branch: String? = null,
    val preview: String = "",
    val lastSeq: Long? = null,
    val model: String? = null
) {
    fun toSummary(): CodeSessionSummary = CodeSessionSummary(
        id = id,
        hostId = hostId,
        harness = HarnessKind.fromId(harness),
        workspace = cwd,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        permissionMode = PermissionMode.fromId(mode),
        model = model,
        preview = preview,
        branch = branch,
        lastSeq = lastSeq
    )

    companion object {
        fun from(summary: CodeSessionSummary): CodeSessionEntity = CodeSessionEntity(
            id = summary.id,
            hostId = summary.hostId,
            harness = summary.harness.id,
            cwd = summary.workspace,
            title = summary.title,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt,
            mode = summary.permissionMode.id,
            branch = summary.branch,
            preview = summary.preview,
            lastSeq = summary.lastSeq,
            model = summary.model
        )
    }
}
