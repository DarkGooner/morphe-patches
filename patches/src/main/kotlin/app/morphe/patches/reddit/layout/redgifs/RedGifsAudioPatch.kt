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
            // Older RedGifs posts have no Reddit hosted video and open in the browser.
            // For these a preview video is set, and the post hint is changed to match newer
            // RedGifs posts, so Reddit plays them in the app.
            addInstructionsWithLabels(
                returnIndex,
                """
                    move-object/from16 v0, p0
                    invoke-static { v0 }, $EXTENSION_CLASS->onLinkCreated(Lcom/reddit/domain/model/Link;)Lcom/reddit/domain/model/Preview;
                    move-result-object v1
                    if-eqz v1, :keep_preview
                    iput-object v1, v0, Lcom/reddit/domain/model/Link;->preview:Lcom/reddit/domain/model/Preview;
                    const-string v1, "rich:video"
                    iput-object v1, v0, Lcom/reddit/domain/model/Link;->postHint:Ljava/lang/String;
                    :keep_preview
                    nop
                """
            )
        }

        // Every video player url, including feed videos that are created without the post.
        VideoUrlsConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getPlaybackUrl(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
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
