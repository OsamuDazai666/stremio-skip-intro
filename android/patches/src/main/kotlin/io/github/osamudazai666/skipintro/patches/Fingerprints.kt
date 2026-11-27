package io.github.osamudazai666.skipintro.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * The player view model method that receives every new player state from Stremio's core:
 *
 *     public final void g(Player player) {
 *         ...
 *         Player.IntroOutro introOutro = player.getIntroOutro();
 *         uz4 segments = h(this, null, introOutro, 1);
 *         ...
 *     }
 */
object PlayerStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lcom/stremio/core/models/Player;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/stremio/core/models/Player;", name = "getIntroOutro"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

/**
 * Works out the intro and credits segments shown by the player (file chapters first, then the
 * core's introOutro). It returns nothing unless the account has Stremio Premium:
 *
 *     Auth auth = profile.getAuth();
 *     if (!isPremium(auth != null ? auth.getUser() : null)) return new uz4(null, null);
 */
object SkipSegmentsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf("L", "Ljava/util/List;", "Lcom/stremio/core/models/Player\$IntroOutro;", "I"),
    filters = listOf(
        methodCall(definingClass = "Lcom/stremio/core/types/profile/Auth;", name = "getUser"),
        methodCall(parameters = listOf("Lcom/stremio/core/types/profile/User;"), returnType = "Z"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
    ),
)
