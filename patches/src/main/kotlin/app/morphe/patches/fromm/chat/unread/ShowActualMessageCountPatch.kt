package app.morphe.patches.fromm.chat.unread

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// ── helper ────────────────────────────────────────────────────────────────

private fun hasConst99(method: com.android.tools.smali.dexlib2.iface.Method): Boolean =
    method.implementation?.instructions?.any { instr ->
        (instr.opcode == Opcode.CONST_16 || instr.opcode == Opcode.CONST) &&
            (instr as? NarrowLiteralInstruction)?.narrowLiteral == 99
    } == true

private fun hasStringDisplay(method: com.android.tools.smali.dexlib2.iface.Method): Boolean =
    method.implementation?.instructions?.any { instr ->
        instr is ReferenceInstruction && instr.reference.toString().let { ref ->
            ref.contains("String;->valueOf(I)") ||
                ref.contains("String;->valueOf(J)") ||
                ref.contains("->getString(I)") ||
                ref.contains("setText(")
        }
    } == true

// ── Fingerprint A: standalone formatter (returns String, any class) ────────
// Catches: fun formatBadge(count: Int): String = if (count > 99) "99+" else ...
private val formatterFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    custom { method, _ ->
        method.parameterTypes.any { it == "I" || it == "J" } &&
            hasConst99(method) && hasStringDisplay(method)
    }
}

// ── Fingerprint B: void display method inside Fromm UI classes ────────────
// Catches: ViewHolder.bind(), DataBinding executeBindings(), @BindingAdapter
// that set text directly without returning a String.
private val voidDisplayFingerprint = fingerprint {
    custom { method, classDef ->
        val type = classDef.type
        // Only look in Fromm's own UI/presentation layer.
        if (!type.startsWith("Lcom/knowmerce/fromm/")) return@custom false
        if (type.contains("/data/") || type.contains("/network/") ||
            type.contains("/db/") || type.contains("/dao/") ||
            type.contains("/datasource/")) return@custom false
        hasConst99(method) && hasStringDisplay(method)
    }
}

// ─────────────────────────────────────────────────────────────────────────

private fun patchCap(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) {
    val instrs = InstructionHelper.getInstructions(method)
    val capIdx = instrs.indexOfFirst { instr ->
        (instr.opcode == Opcode.CONST_16 || instr.opcode == Opcode.CONST) &&
            (instr as? NarrowLiteralInstruction)?.narrowLiteral == 99
    }
    if (capIdx == -1) throw PatchException("99 threshold constant not found")
    val reg = when (instrs[capIdx].opcode) {
        Opcode.CONST_16 -> (instrs[capIdx] as? BuilderInstruction21s)?.registerA ?: 0
        else            -> (instrs[capIdx] as? BuilderInstruction31i)?.registerA ?: 0
    }
    // count > 99 → count > 2_147_483_647: branch never taken → real count shown.
    InstructionHelper.replaceInstruction(method, capIdx, "const v$reg, 0x7fffffff")
}

@Suppress("unused")
val showActualMessageCountPatch = bytecodePatch(
    name = "실제 메시지 수 표시",
    description = "99+로 표시되는 읽지 않은 메시지 수를 실제 숫자로 표시합니다.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        // Try the standalone formatter first (String-returning utility method).
        try {
            patchCap(formatterFingerprint.match().method)
        } catch (_: Exception) { /* not a standalone formatter in this APK */ }

        // Then patch the UI/binding method (void, sets text directly).
        try {
            patchCap(voidDisplayFingerprint.match().method)
        } catch (_: Exception) { /* no matching void display method */ }
    }
}
