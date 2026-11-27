package io.github.osamudazai666.skipintro.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.osamudazai666.skipintro.patches.Constants.COMPATIBILITY_STREMIO
import io.github.osamudazai666.skipintro.patches.Constants.EXTENSION_CLASS

@Suppress("unused")
val skipIntroPatch = bytecodePatch(
    name = "Skip Intro",
    description = "Netflix-style Skip Intro and Next Episode buttons in Stremio's player, using AniSkip (anime) " +
        "and TheIntroDB (series, movies, anime) timestamps. Works without Stremio Premium; " +
        "Stremio's own premium timestamps stay premium-only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_STREMIO)

    extendWith("extensions/extension.mpe")

    execute {
        // 1. Every player state: remember it, and swap in our timestamps when we have them.
        PlayerStateFingerprint.let {
            val moveResult = it.instructionMatches[1]
            val register = moveResult.getInstruction<OneRegisterInstruction>().registerA

            // Later index first, so the earlier index stays valid.
            it.method.addInstructions(
                moveResult.index + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->introOutro(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, Lcom/stremio/core/models/Player${'$'}IntroOutro;
                """,
            )
            it.method.addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onPlayer(Ljava/lang/Object;)V
                """,
            )
        }

        // 2. Draw the skip overlay when we have timestamps (premium accounts unchanged).
        SkipOverlayFingerprint.let {
            val moveResult = it.instructionMatches[3]
            val register = moveResult.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                moveResult.index + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->allowOverlay(Z)Z
                    move-result v$register
                """,
            )
        }

        // 3. Draw our own Netflix-style buttons from the overlay's inputs, and keep Stremio's hidden.
        SkipOverlayComposableFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p12 }, $EXTENSION_CLASS->onOverlay(Ljava/lang/Object;JJLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )
        SkipOverlayVisibleFingerprint.method.addInstructions(
            0,
            """
                const/4 p1, 0x0
            """,
        )

        // 4. Let the segments through when they come from our sources (premium users unchanged).
        SkipSegmentsFingerprint.let {
            val moveResult = it.instructionMatches[2]
            val register = moveResult.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                moveResult.index + 1,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->allow(Z)Z
                    move-result v$register
                """,
            )
        }
    }
}
