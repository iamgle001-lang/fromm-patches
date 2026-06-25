package app.morphe.patches.fromm.chat.deleted

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/*
 * ===================================================================
 * 삭제된 메시지 표시 패치
 * ===================================================================
 *
 * 패치 전략 (3단계):
 *
 *   [패치 A] UPDATE 쿼리에서 content/thumbnail 삭제 부분 제거
 *   [패치 B] SELECT 쿼리에서 deletedAt 필터 제거
 *   [패치 C] INSERT OR REPLACE → INSERT OR IGNORE (재시작 보존)
 * ===================================================================
 */

private val deleteQueryFingerprint = fingerprint {
    strings("UPDATE message SET deletedAt = ?, content = '', thumbnail = '' WHERE hostChatRoomId = ? AND id = ?")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
}

private val selectQueryFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt > ")
    opcodes(Opcode.CONST_STRING)
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryAscFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt >= ")
    opcodes(Opcode.CONST_STRING)
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryDescFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt < ")
    opcodes(Opcode.CONST_STRING)
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val selectQueryDescEqFingerprint = fingerprint {
    strings(" AND (deletedAt IS NULL OR deletedAt = 0) AND hidden = 0 AND createdAt <= ")
    opcodes(Opcode.CONST_STRING)
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;"
    }
}

private val insertReplaceAdapterFingerprint = fingerprint {
    strings("INSERT OR REPLACE INTO `message` (`id`,`hostChatRoomId`")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
    custom { _, classDef ->
        classDef.superclass == "Landroidx/room/EntityInsertionAdapter;"
    }
}

@Suppress("unused")
val showDeletedMessagesPatch = bytecodePatch(
    name = "Show deleted messages",
    description = "Preserves content of deleted messages and shows them in the chat list.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        val deleteMethod = deleteQueryFingerprint.match().method
        val selectMethod = selectQueryFingerprint.match().method
        val selectAscMethod = selectQueryAscFingerprint.match().method
        val selectDescMethod = selectQueryDescFingerprint.match().method
        val selectDescEqMethod = selectQueryDescEqFingerprint.match().method
        val insertMethod = insertReplaceAdapterFingerprint.match().method

        // 패치 A: UPDATE 쿼리 수정 — content/thumbnail 유지
        val deleteInstructions = InstructionHelper.getInstructions(deleteMethod)
        val updateQueryIdx = deleteInstructions.indexOfFirst { instr: BuilderInstruction ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString()?.contains("UPDATE message SET deletedAt = ?, content") == true
        }
        if (updateQueryIdx == -1) throw PatchException("UPDATE query string not found")
        val updateReg = (deleteInstructions[updateQueryIdx] as? BuilderInstruction21c)?.registerA ?: 0
        InstructionHelper.replaceInstruction(
            deleteMethod,
            updateQueryIdx,
            "const-string v$updateReg, \"UPDATE message SET deletedAt = ? WHERE hostChatRoomId = ? AND id = ?\"",
        )

        // 패치 B: SELECT 쿼리 조각 수정 — deletedAt 필터 제거
        fun replaceDeletionFilter(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, suffix: String) {
            val instrs = InstructionHelper.getInstructions(method)
            val idx = instrs.indexOfFirst { instr: BuilderInstruction ->
                instr.opcode == Opcode.CONST_STRING &&
                    (instr as? ReferenceInstruction)?.reference?.toString()?.contains("deletedAt IS NULL OR deletedAt = 0") == true
            }
            if (idx == -1) throw PatchException("deletedAt filter not found in ${method.name}")
            val reg = (instrs[idx] as? BuilderInstruction21c)?.registerA ?: 2
            InstructionHelper.replaceInstruction(method, idx, "const-string v$reg, \" AND hidden = 0 AND createdAt $suffix\"")
        }

        replaceDeletionFilter(selectMethod, "> ")
        replaceDeletionFilter(selectAscMethod, ">= ")
        replaceDeletionFilter(selectDescMethod, "< ")
        replaceDeletionFilter(selectDescEqMethod, "<= ")

        // 패치 C: INSERT OR REPLACE → INSERT OR IGNORE
        val insertInstructions = InstructionHelper.getInstructions(insertMethod)
        val insertQueryIdx = insertInstructions.indexOfFirst { instr: BuilderInstruction ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString()?.contains("INSERT OR REPLACE INTO `message`") == true
        }
        if (insertQueryIdx == -1) throw PatchException("INSERT OR REPLACE query not found")
        val insertReg = (insertInstructions[insertQueryIdx] as? BuilderInstruction21c)?.registerA ?: 0
        InstructionHelper.replaceInstruction(
            insertMethod,
            insertQueryIdx,
            "const-string v$insertReg, \"INSERT OR IGNORE INTO `message` (`id`,`hostChatRoomId`,`userId`,`userType`,`createdAt`,`type`,`content`,`thumbnail`,`deletedAt`,`starChatId`,`code`,`runningTime`,`translationCode`,`translatedMessage`,`hasNick`,`isBestFriend`,`hidden`,`supportMessageVersion`,`supportMessageVersionWhenSaved`,`mentionedMessageId`,`mentionedUserId`,`mentionedContent`,`mentionedTranslationCode`,`mentionedTranslatedMessage`,`mentionedDeletedAt`,`mentionedReportedAt`,`mentioned_emoticonItemId`,`mentioned_emoticonPackId`,`mentioned_emoticonIsAnimated`,`mentioned_emoticonImageUrl`,`mentioned_emoticonThumbnailUrl`,`emoticonItemId`,`emoticonPackId`,`emoticonIsAnimated`,`emoticonImageUrl`,`emoticonThumbnailUrl`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\"",
        )
    }
}
