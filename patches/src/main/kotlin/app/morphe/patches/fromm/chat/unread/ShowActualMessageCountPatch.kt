package app.morphe.patches.fromm.chat.unread

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

// Matches the method that formats an integer count into a display string,
// capping it at 99 (shows "99+" for anything above).
// Strategy: skip string matching (may be a resource) and instead detect
// the CONST_16/CONST instruction that loads the literal 99 threshold.
private val unreadCountCapFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    custom { method, _ ->
        // Must accept at least one int or long parameter.
        if (!method.parameterTypes.any { it == "I" || it == "J" }) return@custom false
        // Must contain a const instruction loading the value 99.
        method.implementation?.instructions?.any { instr ->
            (instr.opcode == Opcode.CONST_16 || instr.opcode == Opcode.CONST) &&
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == 99
        } == true
    }
}

@Suppress("unused")
val showActualMessageCountPatch = bytecodePatch(
    name = "Show actual message count",
    description = "Shows the real unread/message count instead of capping the display at 99+.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        val method = unreadCountCapFingerprint.match().method
        val instrs = InstructionHelper.getInstructions(method)

        // Locate the const instruction that loads 99 (the display cap threshold).
        val capIdx = instrs.indexOfFirst { instr ->
            (instr.opcode == Opcode.CONST_16 || instr.opcode == Opcode.CONST) &&
                (instr as? NarrowLiteralInstruction)?.narrowLiteral == 99
        }
        if (capIdx == -1) throw PatchException("99 threshold constant not found in method")

        // Determine which register holds the constant.
        val reg = when (instrs[capIdx].opcode) {
            Opcode.CONST_16 -> (instrs[capIdx] as? BuilderInstruction21s)?.registerA ?: 0
            else            -> (instrs[capIdx] as? BuilderInstruction31i)?.registerA ?: 0
        }

        // Replace the 99 cap with Integer.MAX_VALUE.
        // "count > 99"           → "count > 2_147_483_647"  (never true in practice)
        // The method therefore always falls through to the normal number→string path.
        InstructionHelper.replaceInstruction(method, capIdx, "const v$reg, 0x7fffffff")
    }
}
