package app.morphe.patches.fromm.chat.deleted

import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.fromm.util.InstructionHelper
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22x
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

// ── Patch C: EntityInsertionAdapter → UPSERT ─────────────────────────────
// Replaces INSERT OR REPLACE with an UPSERT that preserves non-empty content
// and thumbnail. This prevents the mapper (which clears content/thumbnail for
// deleted messages) from overwriting content that Patch A already preserved.
private val insertReplaceAdapterFingerprint = fingerprint {
    strings("INSERT OR REPLACE INTO `message` (`id`,`hostChatRoomId`")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
    custom { _, classDef ->
        classDef.superclass == "Landroidx/room/EntityInsertionAdapter;"
    }
}

// ── Patch A ─────────────────────────────────────────────────────────────
private val deleteQueryFingerprint = fingerprint {
    strings("UPDATE message SET deletedAt = ?, content = '', thumbnail = '' WHERE hostChatRoomId = ? AND id = ?")
    opcodes(Opcode.CONST_STRING, Opcode.RETURN_OBJECT)
}

// ── Patch B: paginated SELECT queries (cursor variants) ──────────────────
// NOTE: These fingerprints do NOT require "hidden = 0" because ShowHiddenMessagesPatch
// may have already replaced it with "1 = 1". We match only on the deletedAt filter
// + the specific createdAt cursor direction to stay robust regardless of patch order.
private val selectQueryFingerprint = fingerprint {
    custom { method, classDef ->
        if (classDef.type != "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;") return@custom false
        method.implementation?.instructions?.any { instr ->
            if (instr !is ReferenceInstruction) return@any false
            val r = instr.reference.toString()
            r.contains("deletedAt IS NULL OR deletedAt = 0") &&
                r.contains("createdAt > ") && !r.contains("createdAt >= ")
        } == true
    }
}

private val selectQueryAscFingerprint = fingerprint {
    custom { method, classDef ->
        if (classDef.type != "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;") return@custom false
        method.implementation?.instructions?.any { instr ->
            if (instr !is ReferenceInstruction) return@any false
            val r = instr.reference.toString()
            r.contains("deletedAt IS NULL OR deletedAt = 0") &&
                r.contains("createdAt >= ")
        } == true
    }
}

private val selectQueryDescFingerprint = fingerprint {
    custom { method, classDef ->
        if (classDef.type != "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;") return@custom false
        method.implementation?.instructions?.any { instr ->
            if (instr !is ReferenceInstruction) return@any false
            val r = instr.reference.toString()
            r.contains("deletedAt IS NULL OR deletedAt = 0") &&
                r.contains("createdAt < ") && !r.contains("createdAt <= ")
        } == true
    }
}

private val selectQueryDescEqFingerprint = fingerprint {
    custom { method, classDef ->
        if (classDef.type != "Lcom/knowmerce/fromm/data/datasource/db/dao/fan/MessageDao_Impl;") return@custom false
        method.implementation?.instructions?.any { instr ->
            if (instr !is ReferenceInstruction) return@any false
            val r = instr.reference.toString()
            r.contains("deletedAt IS NULL OR deletedAt = 0") &&
                r.contains("createdAt <= ")
        } == true
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

// ── Patch F: Stop UI message factory from creating K8/b deleted placeholder ──
// Nd/a::S(f9/c→K8/k) is the factory that converts the intermediate message
// model to the UI domain model.  When f9/c.k (deletedAt Date) is non-null it
// creates K8/b — which the UI renders as "삭제된 메시지" — and returns early.
// Fix: NOP the iget-object that loads f9/c.k into the condition register so
// that register stays null → the subsequent if-eqz always branches past the
// K8/b creation block → a normal K8/f (with preserved content) is built instead.
private val deletedMessageUiFactoryFingerprint = fingerprint {
    returns("LK8/k;")
    custom { method, _ ->
        if (method.parameterTypes.size != 1 || method.parameterTypes[0] != "Lf9/c;") return@custom false
        method.implementation?.instructions?.any { instr ->
            instr.opcode == Opcode.NEW_INSTANCE &&
                (instr as? ReferenceInstruction)?.reference?.toString() == "LK8/b;"
        } == true
    }
}

// ── Patch E: API response mapper clears content/thumbnail for deleted msgs ──
// The mapper (b9/a#f → Nd/a#b0) checks if deletedAt != null and replaces
// content and thumbnail with "" before constructing MessageEntity.
// This fingerprint targets the method that takes one parameter (the intermediate
// network model) and returns MessageEntity, containing an empty-string const
// and multiple Date.getTime() calls (for date → Long conversion).
private val messageEntityMapperFingerprint = fingerprint {
    returns("Lcom/knowmerce/fromm/data/datasource/db/entity/fan/MessageEntity;")
    custom { method, _ ->
        if (method.parameterTypes.size != 1) return@custom false
        val instrs = method.implementation?.instructions ?: return@custom false
        val hasEmptyStr = instrs.any { instr ->
            instr.opcode == Opcode.CONST_STRING &&
                (instr as? ReferenceInstruction)?.reference?.toString() == ""
        }
        if (!hasEmptyStr) return@custom false
        instrs.count { instr ->
            instr is ReferenceInstruction &&
                instr.reference.toString() == "Ljava/util/Date;->getTime()J"
        } >= 2
    }
}

// ── Patch D2: deletedAt property accessor (fallback) ─────────────────────
// If R8 inlines isDeleted() into callers, patching the underlying Long?
// accessor makes every surviving null-check on deletedAt see null.
// Also handles ViewModels that read deletedAt directly without isDeleted().
private val deletedAtGetterFingerprint = fingerprint {
    returns("Ljava/lang/Long;")
    custom { method, _ ->
        method.parameterTypes.isEmpty() &&
            (method.implementation?.instructions?.count() ?: 0) <= 5 &&
            method.implementation?.instructions?.any { instr ->
                instr is ReferenceInstruction &&
                    instr.reference.toString().contains("->deletedAt:Ljava/lang/Long;")
            } == true
    }
}

// ─────────────────────────────────────────────────────────────────────────

@Suppress("unused")
val showDeletedMessagesPatch = bytecodePatch(
    name = "삭제된 메시지 표시",
    description = "삭제된 메시지의 내용을 보존하여 채팅 목록에 표시합니다.",
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
        // Wrapped in try/catch: PreserveDeletedContentPatch may have already
        // replaced this SQL, in which case the fingerprint won't match.
        try {
            val deleteMethod = deleteQueryFingerprint.match().method
            val deleteInstrs = InstructionHelper.getInstructions(deleteMethod)
            val updateIdx = deleteInstrs.indexOfFirst { instr: BuilderInstruction ->
                instr.opcode == Opcode.CONST_STRING &&
                    (instr as? ReferenceInstruction)?.reference?.toString()
                        ?.contains("UPDATE message SET deletedAt = ?, content") == true
            }
            if (updateIdx != -1) {
                val updateReg = (deleteInstrs[updateIdx] as? BuilderInstruction21c)?.registerA ?: 0
                InstructionHelper.replaceInstruction(
                    deleteMethod,
                    updateIdx,
                    // CASE WHEN: if content already starts with "[삭제됨]" (from Patch E or a
                // previous run), keep it as-is to avoid "[삭제됨] [삭제됨]" double-prefix.
                // Otherwise prepend "[삭제됨] " so the original content is preserved and visible.
                "const-string v$updateReg, \"UPDATE message SET deletedAt = ?, content = CASE WHEN content LIKE '[삭제됨]%' THEN content ELSE '[삭제됨] ' || COALESCE(content, '') END WHERE hostChatRoomId = ? AND id = ?\"",
                )
            }
        } catch (_: Exception) { /* already patched by PreserveDeletedContentPatch */ }

        // ── Patch B: remove deletedAt filter from all SELECT queries ────
        removeDeletedAtFilter(selectQueryFingerprint.match().method)
        removeDeletedAtFilter(selectQueryAscFingerprint.match().method)
        removeDeletedAtFilter(selectQueryDescFingerprint.match().method)
        removeDeletedAtFilter(selectQueryDescEqFingerprint.match().method)
        try {
            removeDeletedAtFilter(selectQueryNoCursorFingerprint.match().method)
        } catch (_: Exception) { /* no non-cursor query in this APK version */ }

        // ── Patch C: INSERT OR REPLACE → INSERT OR IGNORE ───────────────
        // Protects Patch A's "[삭제됨] 원래내용" from being overwritten by API re-sync.
        // API re-sync sends content="" (server clears it), but with INSERT OR IGNORE
        // the existing row is kept unchanged, preserving the original content.
        // Rows that already have "[삭제됨]" (from Patch E via a prior v1.2.18 run)
        // are also protected — they stay as "[삭제됨]" instead of being cleared.
        try {
            val insertMethod = insertReplaceAdapterFingerprint.match().method
            val insertInstrs = InstructionHelper.getInstructions(insertMethod)
            val insertIdx = insertInstrs.indexOfFirst { instr: BuilderInstruction ->
                instr.opcode == Opcode.CONST_STRING &&
                    (instr as? ReferenceInstruction)?.reference?.toString()
                        ?.contains("INSERT OR REPLACE INTO `message`") == true
            }
            if (insertIdx != -1) {
                val insertReg = (insertInstrs[insertIdx] as? BuilderInstruction21c)?.registerA ?: 0
                val original = (insertInstrs[insertIdx] as? ReferenceInstruction)
                    ?.reference?.toString()
                if (original != null) {
                    val patched = original.replace("INSERT OR REPLACE INTO", "INSERT OR IGNORE INTO")
                    val escaped = patched
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                    InstructionHelper.replaceInstruction(
                        insertMethod,
                        insertIdx,
                        "const-string v$insertReg, \"$escaped\"",
                    )
                }
            }
        } catch (_: Exception) { /* adapter not found or already patched */ }

        // ── Patch D / D2 disabled ───────────────────────────────────────
        // isDeletedGetterFingerprint and deletedAtGetterFingerprint are too broad:
        // they match any no-arg getter that reads a `deletedAt:Long?` field.
        // Other entities (ChatRoom, User, etc.) may also have such a field.
        // Patching the wrong getter causes null-related crashes at app startup.
        // Disabled until we can constrain these fingerprints to MessageEntity.

        // ── Patch F: Replace K8/b (deleted placeholder) with K8/f (reply/mention format) ──
        // The factory Nd/a.S(f9/c→K8/k) creates K8/b when deletedAt is non-null,
        // rendering it as "삭제된 메시지" in the UI.
        // Fix: Insert K8/f (Mention) creation code BEFORE the K8/b block so it executes
        // first and returns early. The K8/b block becomes dead code.
        //
        // The inserted code:
        //  1. Creates K8/e (mentionedMessage) with the deleted content in the quote box
        //  2. Creates K8/f (Mention/Reply style) referencing that K8/e
        //  3. Returns the K8/f immediately
        //
        // Register layout at this point (loaded at factory method start):
        //   v0  = f9/c (parameter)
        //   v8  = f9/c.c (createdAt Date)
        //   v9  = f9/c.b (hostChatRoomId String)
        //   v10 = f9/c.g (translationCode String — NOT content)
        //   content = f9/c.e (loaded via iget-object in the inserted block)
        //   v11 = f9/c.a (messageId String)
        // Method has .locals 33, so v13-v32 are free to use.
        try {
            val factoryMethod = deletedMessageUiFactoryFingerprint.match().method
            val factoryInstrs = InstructionHelper.getInstructions(factoryMethod)
            val newInstIdx = factoryInstrs.indexOfFirst { instr ->
                instr.opcode == Opcode.NEW_INSTANCE &&
                    (instr as? ReferenceInstruction)?.reference?.toString() == "LK8/b;"
            }
            if (newInstIdx != -1) {
                // Build K8/e + K8/f before the K8/b block.
                // NOTE: addInstructions does NOT support internal labels.
                // All code must be label-free (no if-*, goto, etc.).
                //
                // Register layout confirmed from Nd/a.smali at K8/b insertion point:
                //   v0  = f9/c (p0 was moved to v0 at method start)
                //   v8  = f9/c.c (createdAt Date)
                //   v9  = f9/c.b (hostChatRoomId String)
                //   v10 = f9/c.g (translationCode String — NOT content)
                //   v11 = f9/c.a (messageId String)
                //   v12 = null (0x0, unused from here)
                //   .locals 33 → v0-v32 available
                //
                // NOTE on register encoding limits:
                //   iget-object uses format 22c with 4-bit registers (0-15 only).
                //   So we CANNOT do `iget-object v16, v0, ...` (v16 > 15 → VerifyError).
                //   Workaround: load into v12 (≤ 15), then move-object/from16 to v16.
                //   v12 is safe to clobber here (it was null, and dead K8/b code that
                //   uses new-instance v12 is unreachable after our return-object).
                //
                // K8/e<init>(String msgId, String userId, String content,
                //             Map translated, Z isDeleted, Z isReported, K8/D emoticonItem)
                //   → range {v13..v20}  (8 registers = this + 7 params)
                //
                // K8/f<init>(String msgId, String hostChatRoomId, Date createdAt,
                //             Boolean hasReply, String code, String content,
                //             Z hasNick, Map translated, K8/e mentionedMsg, K8/D emoticonItem)
                //   → range {v21..v31}  (11 registers = this + 10 params)
                //
                // Simplifications to stay label-free:
                //   - userId = messageId (v11), always non-null
                //   - content = f9/c.e (actual preserved content from Patches A/E)
                //   - hasReply = Boolean.TRUE → triggers reply-box rendering
                //   - K8/f.g content = "" → main text empty, content shown in reply box only
                val smali = """
                    new-instance v13, LK8/e;
                    move-object v14, v11
                    move-object v15, v11
                    iget-object v12, v0, Lf9/c;->e:Ljava/lang/String;
                    move-object/from16 v16, v12
                    const/16 v17, 0x0
                    const/16 v18, 0x0
                    const/16 v19, 0x0
                    const/16 v20, 0x0
                    invoke-direct/range {v13 .. v20}, LK8/e;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;ZZLK8/D;)V
                    new-instance v21, LK8/f;
                    move-object/from16 v22, v11
                    move-object/from16 v23, v9
                    move-object/from16 v24, v8
                    sget-object v25, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    const-string v26, ""
                    const-string v27, ""
                    const/16 v28, 0x0
                    const/16 v29, 0x0
                    move-object/from16 v30, v13
                    const/16 v31, 0x0
                    invoke-direct/range {v21 .. v31}, LK8/f;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/util/Date;Ljava/lang/Boolean;Ljava/lang/String;Ljava/lang/String;ZLjava/util/Map;LK8/e;LK8/D;)V
                    return-object v21
                """.trimIndent()
                InstructionHelper.addInstructions(factoryMethod, newInstIdx, smali)
            }
        } catch (_: Exception) { /* factory not found or structure changed */ }

        // ── Patch E: API response mapper — preserve "[삭제됨]" for deleted msgs ──
        // The mapper checks deletedAt != null and sets content="" and thumbnail="".
        // Fix: Replace const-string "" with "[삭제됨]" so the DB stores the indicator
        // even when messages are fetched fresh from the API.
        // const-string → const-string replacement: same type, no VerifyError possible.
        try {
            val mapperMethod = messageEntityMapperFingerprint.match().method
            val mapperInstrs = InstructionHelper.getInstructions(mapperMethod)
            data class Mod(val idx: Int, val reg: Int)
            val mods = mutableListOf<Mod>()
            mapperInstrs.forEachIndexed { idx, instr: BuilderInstruction ->
                if (instr.opcode == Opcode.CONST_STRING &&
                    (instr as? ReferenceInstruction)?.reference?.toString() == "") {
                    val reg = (instr as? BuilderInstruction21c)?.registerA ?: 0
                    mods.add(Mod(idx, reg))
                }
            }
            mods.forEach { (idx, reg) ->
                InstructionHelper.replaceInstruction(
                    mapperMethod,
                    idx,
                    "const-string v$reg, \"[삭제됨]\"",
                )
            }
        } catch (_: Exception) { /* mapper not found */ }
    }
}
