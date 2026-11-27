package io.github.osamudazai666.skipintro.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchBeforeWithin
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

/**
 * The player screen draws the Skip Intro / credits overlay only for Stremio Premium accounts:
 *
 *     if (isPremium.getValue().booleanValue()) {
 *         ...
 *         SkipOverlay(..., time, duration, intro, outro, seriesInfo, nextVideo, settings.getBingeWatching(), ...);
 *     }
 *
 * Anchored on the overlay call, found by its parameter types.
 */
private val SKIP_OVERLAY_PARAMETERS = listOf(
    "L", "J", "J", "L", "L",
    "Lcom/stremio/core/types/resource/Video\$SeriesInfo;",
    "Lcom/stremio/core/types/resource/Video;",
    "Z", "L", "L", "L", "F", "F", "F", "L", "L", "I",
)

object SkipOverlayFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(returnType = "V", parameters = SKIP_OVERLAY_PARAMETERS),
        methodCall(
            definingClass = "Lcom/stremio/core/types/profile/Profile\$Settings;",
            name = "getBingeWatching",
            location = MatchBeforeWithin(60),
        ),
        methodCall(
            definingClass = "Ljava/lang/Boolean;",
            name = "booleanValue",
            location = MatchBeforeWithin(80),
        ),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
    ),
)

/**
 * The skip overlay composable itself:
 *
 *     SkipOverlay(modifier, time, duration, intro, outro, seriesInfo, nextVideo, bingeWatching,
 *                 onSkipIntro, onNext, onDismiss, ...)
 */
object SkipOverlayComposableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = SKIP_OVERLAY_PARAMETERS,
)

/** {@code static void setVisible(MutableState<Boolean> state, boolean visible)} next to the overlay. */
object SkipOverlayVisibleFingerprint : Fingerprint(
    classFingerprint = SkipOverlayComposableFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
)
