/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.redgifs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedGifsAudioPatch;"

@Suppress("unused")
val redGifsAudioPatch = bytecodePatch(
    name = "Enable RedGifs audio",
    description = "Adds an option to play RedGifs videos with sound."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        // Start fetching the RedGifs video url as soon as a post is loaded,
        // so it's ready before the video is first played.
        LinkConstructorFingerprint.method.apply {
            val returnIndex = implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            }

            addInstructions(
                returnIndex,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, Lcom/reddit/domain/model/Link;->url:Ljava/lang/String;
                    invoke-static { v0 }, $EXTENSION_CLASS->prefetch(Ljava/lang/String;)V
                """
            )
        }

        // Feed videos.
        FeedVideoElementFingerprint.let {
            it.method.apply {
                val dashUrlIndex = it.instructionMatches[2].index + 1
                val dashUrlRegister = getInstruction<OneRegisterInstruction>(dashUrlIndex).registerA

                // Range invoke, because this method uses registers above v15.
                addInstructions(
                    dashUrlIndex + 1,
                    """
                        invoke-static/range { v$dashUrlRegister .. v$dashUrlRegister }, $EXTENSION_CLASS->getFeedVideoUrl(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$dashUrlRegister
                    """
                )

                // Registers are copied to v0 first, because parameter registers can be above v15.
                addInstructions(
                    0,
                    """
                        move-object/from16 v0, p0
                        invoke-virtual { v0 }, Lcom/reddit/domain/model/Link;->getUrl()Ljava/lang/String;
                        move-result-object v0
                        invoke-static { v0 }, $EXTENSION_CLASS->setFeedPostUrl(Ljava/lang/String;)V
                    """
                )
            }
        }

        // Post detail and full screen videos.
        LinkVideoUrlFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                invoke-virtual { v0 }, Lcom/reddit/domain/model/Link;->getUrl()Ljava/lang/String;
                move-result-object v0
                invoke-static { v0 }, $EXTENSION_CLASS->getVideoUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )

        LinkMediaVideoUrlFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p4
                invoke-static { v0 }, $EXTENSION_CLASS->getVideoUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
                :original
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
