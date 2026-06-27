package app.morphe.patches.fromm.chat.hidden

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// ── helper ────────────────────────────────────────────────────────────────

private fun hasString(method: com.android.tools.smali.dexlib2.iface.Method, sub: String): Boolean =
    method.implementation?.instructions?.any { instr ->
        instr is ReferenceInstruction && instr.reference.toString().contains(sub)
    } == true

// ── Fingerprints ──────────────────────────────────────────────────────────

// getMatchedAfterMessage: ascending keyword search
private val searchAfterFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "ORDER BY createdAt ASC LIMIT 1") &&
            hasString(method, "LIKE")
    }
}

// getMatchedBeforeMessage: descending keyword search
private val searchBeforeFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "ORDER BY createdAt DESC LIMIT 1") &&
            hasString(method, "LIKE")
    }
}

// getMediaMessagesGreaterThan: cursor fragment createdAt >
private val mediaGtFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "createdAt > ") &&
            !hasString(method, "createdAt >= ") &&
            !hasString(method, "LIKE")
    }
}

// getMediaMessagesGreaterThanAndEqualTo: cursor fragment createdAt >=
private val mediaGteFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "createdAt >= ") &&
            !hasString(method, "LIKE")
    }
}

// getMediaMessagesLessThan: cursor fragment createdAt <
private val mediaLtFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "createdAt < ") &&
            !hasString(method, "createdAt <= ") &&
            !hasString(method, "LIKE") &&
            !hasString(method, "? = 0 OR hidden = 0")
    }
}

// getMediaMessagesLessThanAndEqualTo: cursor fragment createdAt <=
private val mediaLteFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "createdAt <= ") &&
            !hasString(method, "LIKE") &&
            !hasString(method, "? = 0 OR hidden = 0")
    }
}

// getMessagesLessThan: conditional hidden filter, createdAt < ?
private val conditionalLtFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "? = 0 OR hidden = 0") &&
            hasString(method, "createdAt < ?") &&
            !hasString(method, "createdAt <= ?")
    }
}

// getMessagesLessThanAndEqualTo: conditional hidden filter, createdAt <= ?
private val conditionalLteFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "? = 0 OR hidden = 0") &&
            hasString(method, "createdAt <= ?")
    }
}

// getTranslatableArtiMessages: userType IN filter with hidden = 0
private val translatableFp = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;" &&
            hasString(method, "hidden = 0") &&
            hasString(method, "userType IN")
    }
}

// ── Core helper ───────────────────────────────────────────────────────────

private fun removeHiddenFilter(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val instrs = InstructionHelper.getInstructions(method)
    data class Mod(val idx: Int, val smali: String)
    val mods = mutableListOf<Mod>()
    instrs.forEachIndexed { idx, instr: BuilderInstruction ->
        if (instr.opcode != Opcode.CONST_STRING) return@forEachIndexed
        val ref = (instr as? ReferenceInstruction)?.reference?.toString() ?: return@forEachIndexed
        if (!ref.contains("hidden = 0")) return@forEachIndexed
        val newStr = ref.replace("hidden = 0", "1 = 1")
        val reg = (instr as? BuilderInstruction21c)?.registerA ?: 0
        val escaped = newStr
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        mods.add(Mod(idx, "const-string v$reg, \"$escaped\""))
    }
    mods.forEach { (idx, smali) ->
        InstructionHelper.replaceInstruction(method, idx, smali)
    }
}

// ── Patch ─────────────────────────────────────────────────────────────────

@Suppress("unused")
val showHiddenMessagesPatch = bytecodePatch(
    name = "숨겨진 메시지 표시",
    description = "서버에 의해 숨겨진 메시지를 채팅방에 표시합니다.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        try { removeHiddenFilter(searchAfterFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(searchBeforeFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(mediaGtFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(mediaGteFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(mediaLtFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(mediaLteFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(conditionalLtFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(conditionalLteFp.match().method) } catch (_: Exception) {}
        try { removeHiddenFilter(translatableFp.match().method) } catch (_: Exception) {}
    }
}
