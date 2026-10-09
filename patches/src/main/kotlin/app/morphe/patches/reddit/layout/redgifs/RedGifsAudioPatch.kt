/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.redgifs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

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

            // All registers are unused at the final return, so v0 and v1 can be overwritten.
            addInstructions(
                returnIndex,
                """
                    move-object/from16 v0, p0
                    invoke-virtual { v0 }, Lcom/reddit/domain/model/Link;->getKindWithId()Ljava/lang/String;
                    move-result-object v1
                    iget-object v0, v0, Lcom/reddit/domain/model/Link;->url:Ljava/lang/String;
                    invoke-static { v1, v0 }, $EXTENSION_CLASS->prefetch(Ljava/lang/String;Ljava/lang/String;)V
                """
            )
        }

        // Feed videos. The GraphQL feed has no post url, so the RedGifs post is found by its link id.
        // Before the super constructor call only v0 is free, so the first seven parameters
        // (linkId .. videoUrl) are passed as a register range.
        VideoElementConstructorFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p7 }, $EXTENSION_CLASS->getVideoElementUrl(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                move-object/from16 p7, v0
                sget-object v0, Lcom/reddit/feeds/model/VideoElement${'$'}Type;->MP4:Lcom/reddit/feeds/model/VideoElement${'$'}Type;
                move-object/from16 p5, v0
                :original
                nop
            """
        )

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
