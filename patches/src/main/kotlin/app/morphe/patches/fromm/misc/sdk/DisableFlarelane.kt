package app.morphe.patches.fromm.misc.sdk

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private val flarelaneInitFingerprint = fingerprint {
    custom { method, _ ->
        method.implementation?.instructions?.any { instr ->
            instr is ReferenceInstruction &&
                instr.reference.toString().startsWith("Lcom/flarelane/")
        } == true
    }
}

@Suppress("unused")
val disableFlarelane = bytecodePatch(
    name = "Disable Flarelane push",
    description = "Disables Flarelane push marketing SDK initialization.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        val method = try {
            flarelaneInitFingerprint.match().method
        } catch (_: Exception) {
            return@execute // Flarelane SDK not present in this APK version
        }
        val instructions = InstructionHelper.getInstructions(method)

        val flarelaneIdx = instructions.indexOfFirst { instr: BuilderInstruction ->
            instr.opcode == Opcode.INVOKE_STATIC &&
                (instr as? ReferenceInstruction)?.reference?.toString()?.startsWith("Lcom/flarelane/") == true
        }
        if (flarelaneIdx == -1) return@execute // call site not found, skip

        InstructionHelper.replaceInstruction(method, flarelaneIdx, "nop")
    }
}
