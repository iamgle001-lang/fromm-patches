package app.morphe.patches.fromm.chat.deleted

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// Targets the SharedSQLiteStatement inner class that holds the
// "UPDATE message SET deletedAt = ?, content = '', thumbnail = ''" SQL.
private val updateDeletedFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl\$5;" &&
            method.implementation?.instructions?.any { instr ->
                instr is ReferenceInstruction &&
                    instr.reference.toString().contains("content = ''")
            } == true
    }
}

@Suppress("unused")
val preserveDeletedContentPatch = bytecodePatch(
    name = "삭제 메시지 내용 보존",
    description = "메시지가 삭제될 때 내용을 지우지 않고 보존합니다. '삭제된 메시지 표시' 패치와 함께 사용하세요.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        val method = updateDeletedFp.match().method
        val instrs = InstructionHelper.getInstructions(method)

        val idx = instrs.indexOfFirst { instr ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString()
                    ?.contains("content = ''") == true
        }
        if (idx < 0) return@execute

        val reg = (instrs[idx] as? BuilderInstruction21c)?.registerA ?: 0
        InstructionHelper.replaceInstruction(
            method,
            idx,
            "const-string v$reg, \"UPDATE message SET deletedAt = ? WHERE hostChatRoomId = ? AND id = ?\"",
        )
    }
}
