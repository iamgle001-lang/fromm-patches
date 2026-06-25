package app.morphe.patches.fromm.misc.update

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private val checkAvailabilityFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type.startsWith("Lcom/knowmerce/fromm/domain/fan/usecases/common/") &&
            method.implementation?.instructions?.any { instr ->
                instr is ReferenceInstruction &&
                    instr.reference.toString().contains("getFanForceUpdateVersion")
            } == true
    }
}

@Suppress("unused")
val forceUpdateBypassPatch = bytecodePatch(
    name = "Force update bypass",
    description = "Skips mandatory version update check by always returning Success.",
) {
    compatibleWith("com.knowmerce.fromm.fan")

    execute {
        val method = checkAvailabilityFingerprint.match().method
        val instructions = InstructionHelper.getInstructions(method)

        // Find the Success singleton field reference so we can return it directly.
        val successInstr = instructions.lastOrNull { instr: BuilderInstruction ->
            instr.opcode == Opcode.SGET_OBJECT &&
                (instr as? ReferenceInstruction)?.reference?.toString()?.contains("usecases/common/g;->a:") == true
        } as? BuilderInstruction21c
            ?: throw PatchException("Success sget not found")

        val successRef = successInstr.reference.toString()

        // Insert at position 0: load Success and return immediately.
        // This skips all force-update logic regardless of method structure.
        InstructionHelper.addInstructions(
            method,
            0,
            """
            sget-object v0, $successRef
            return-object v0
            """.trimIndent(),
        )
    }
}
