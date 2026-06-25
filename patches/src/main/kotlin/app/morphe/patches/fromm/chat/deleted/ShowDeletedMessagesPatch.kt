package app.morphe.patches.fromm.chat.deleted

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// ── Patch A ─────────────────────────────────────────────────────────────
private val deleteQueryFingerprint = fingerprint {
    strings("UPDATE message SET deletedAt = ?, content = '', thumbnail = '' WHERE hostChatRoomId = ? AND id = ?")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
}

// ── Patch B: paginated SELECT queries (cursor variants) ──────────────────
private val selectQueryFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt > ")
    opcodes(Opcode.CONST_STRING)
    custom { _, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryAscFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt >= ")
    opcodes(Opcode.CONST_STRING)
    custom { _, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryDescFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt < ")
    opcodes(Opcode.CONST_STRING)
    custom { _, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryDescEqFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt <= ")
    opcodes(Opcode.CONST_STRING)
    custom { _, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

// ── Patch B2: initial/non-cursor SELECT query ────────────────────────────
// Catches queries that have the deletedAt filter but no createdAt cursor.
private val selectQueryNoCursorFingerprint = fingerprint {
    custom { method, classDef ->
        if (classDef.type != "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;") return@custom false
        val instrs = method.implementation?.instructions ?: return@custom false
        val hasFilter = instrs.any { instr ->
            instr is ReferenceInstruction &&
                instr.reference.toString().contains("deletedAt IS NULL OR deletedAt = 0")
        }
        val hasCursor = instrs.any { instr ->
            instr is ReferenceInstruction && instr.reference.toString().let { r ->
                r.contains("createdAt > ") || r.contains("createdAt >= ") ||
                    r.contains("createdAt < ") || r.contains("createdAt <= ")
            }
        }
        hasFilter && !hasCursor
    }
}

// ── Patch C ──────────────────────────────────────────────────────────────
private val insertReplaceAdapterFingerprint = fingerprint {
    strings("INSERT OR REPLACE INTO `message` (`id`,`hostChatRoomId`")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
    custom { _, classDef ->
        classDef.superclass == "Landroidx/room/EntityInsertionAdapter;"
    }
}

// ── Patch D: UI-layer isDeleted getter ───────────────────────────────────
// Finds a no-arg boolean getter that reads the deletedAt: Long? field,
// which the UI/ViewModel uses to hide or replace deleted message content.
private val isDeletedGetterFingerprint = fingerprint {
    returns("Z")
    custom { method, _ ->
        method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any { instr ->
                instr is ReferenceInstruction &&
                    instr.reference.toString().contains("->deletedAt:Ljava/lang/Long;")
            } == true
    }
}

// ─────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val showDeletedMessagesPatch = bytecodePatch(
    name = "Show deleted messages",
    description = "Preserves content of deleted messages and shows them in the chat list.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        // Helper: remove " AND (deletedAt IS NULL OR deletedAt = 0)" from every
        // matching const-string in a method — works for both SQL fragments and
        // full SQL strings built in one const-string instruction.
        fun removeDeletedAtFilter(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
            val instrs = InstructionHelper.getInstructions(method)
            data class Mod(val idx: Int, val reg: Int, val smali: String)
            val mods = mutableListOf<Mod>()
            instrs.forEachIndexed { idx, instr: BuilderInstruction ->
                if (instr.opcode != Opcode.CONST_STRING) return@forEachIndexed
                val ref = (instr as? ReferenceInstruction)?.reference?.toString() ?: return@forEachIndexed
                if (!ref.contains("deletedAt IS NULL OR deletedAt = 0")) return@forEachIndexed
                val newStr = ref.replace(" AND (deletedAt IS NULL OR deletedAt = 0)", "")
                val reg = (instr as? BuilderInstruction21c)?.registerA ?: 0
                // Escape special characters so the smali compiler doesn't choke on
                // actual newlines / backslashes that Room embeds in multi-line @Query strings.
                val escaped = newStr
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                mods.add(Mod(idx, reg, "const-string v$reg, \"$escaped\""))
            }
            mods.forEach { (idx, _, smali) ->
                InstructionHelper.replaceInstruction(method, idx, smali)
            }
        }

        // ── Patch A: UPDATE query — keep content/thumbnail ──────────────
        val deleteMethod = deleteQueryFingerprint.match().method
        val deleteInstrs = InstructionHelper.getInstructions(deleteMethod)
        val updateIdx = deleteInstrs.indexOfFirst { instr: BuilderInstruction ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString()
                    ?.contains("UPDATE message SET deletedAt = ?, content") == true
        }
        if (updateIdx == -1) throw PatchException("UPDATE query string not found")
        val updateReg = (deleteInstrs[updateIdx] as? BuilderInstruction21c)?.registerA ?: 0
        InstructionHelper.replaceInstruction(
            deleteMethod,
            updateIdx,
            "const-string v$updateReg, \"UPDATE message SET deletedAt = ? WHERE hostChatRoomId = ? AND id = ?\"",
        )

        // ── Patch B: remove deletedAt filter from all SELECT queries ────
        removeDeletedAtFilter(selectQueryFingerprint.match().method)
        removeDeletedAtFilter(selectQueryAscFingerprint.match().method)
        removeDeletedAtFilter(selectQueryDescFingerprint.match().method)
        removeDeletedAtFilter(selectQueryDescEqFingerprint.match().method)
        try {
            removeDeletedAtFilter(selectQueryNoCursorFingerprint.match().method)
        } catch (_: Exception) { /* no non-cursor query in this APK version */ }

        // ── Patch C: INSERT OR REPLACE → INSERT OR IGNORE ───────────────
        val insertMethod = insertReplaceAdapterFingerprint.match().method
        val insertInstrs = InstructionHelper.getInstructions(insertMethod)
        val insertIdx = insertInstrs.indexOfFirst { instr: BuilderInstruction ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString()
                    ?.contains("INSERT OR REPLACE INTO `message`") == true
        }
        if (insertIdx == -1) throw PatchException("INSERT OR REPLACE query not found")
        val insertReg = (insertInstrs[insertIdx] as? BuilderInstruction21c)?.registerA ?: 0
        InstructionHelper.replaceInstruction(
            insertMethod,
            insertIdx,
            "const-string v$insertReg, \"INSERT OR IGNORE INTO `message` (`id`,`hostChatRoomId`,`userId`,`userType`,`createdAt`,`type`,`content`,`thumbnail`,`deletedAt`,`starChatId`,`code`,`runningTime`,`translationCode`,`translatedMessage`,`hasNick`,`isBestFriend`,`hidden`,`supportMessageVersion`,`supportMessageVersionWhenSaved`,`mentionedMessageId`,`mentionedUserId`,`mentionedContent`,`mentionedTranslationCode`,`mentionedTranslatedMessage`,`mentionedDeletedAt`,`mentionedReportedAt`,`mentioned_emoticonItemId`,`mentioned_emoticonPackId`,`mentioned_emoticonIsAnimated`,`mentioned_emoticonImageUrl`,`mentioned_emoticonThumbnailUrl`,`emoticonItemId`,`emoticonPackId`,`emoticonIsAnimated`,`emoticonImageUrl`,`emoticonThumbnailUrl`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\"",
        )

        // ── Patch D: UI-layer isDeleted() getter → always false ─────────
        // Even after DB patches, the UI/ViewModel checks message.isDeleted
        // (computed from deletedAt) and hides or replaces deleted content.
        // Make the getter always return false so the UI shows the original.
        try {
            val isDeletedMethod = isDeletedGetterFingerprint.match().method
            InstructionHelper.replaceInstructions(
                isDeletedMethod,
                0,
                """
                const/4 v0, 0x0
                return v0
                """.trimIndent(),
            )
        } catch (_: Exception) { /* getter not found or already inlined */ }
    }
}
