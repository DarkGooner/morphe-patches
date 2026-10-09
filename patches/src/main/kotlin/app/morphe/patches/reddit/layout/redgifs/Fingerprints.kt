/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.redgifs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Reddit has no RedGifs specific code. A RedGifs post is a link post whose playable media
 * is Reddit's own audio-less transcode: preview.reddit_video_preview.dash_url, falling back
 * to the preview image mp4 variant. Both methods below pick that playback url.
 */
private val PREVIEW_VIDEO_URL_FILTERS = listOf(
    methodCall(
        definingClass = "Lcom/reddit/domain/model/Preview;",
        name = "getRedditVideoPreview",
        returnType = "Lcom/reddit/domain/model/RedditVideo;"
    ),
    methodCall(
        definingClass = "Lcom/reddit/domain/model/RedditVideo;",
        name = "getDashUrl",
        returnType = "Ljava/lang/String;"
    ),
    methodCall(
        definingClass = "Lcom/reddit/domain/model/Variants;",
        name = "getMp4",
        returnType = "Lcom/reddit/domain/model/Variant;"
    )
)

/**
 * 2026.22.0: ez40.b(Link, msb0)
 * 2026.24.0: y260.b(Link, m4d0)
 */
internal object LinkVideoUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Lcom/reddit/domain/model/Link;", "L"),
    filters = PREVIEW_VIDEO_URL_FILTERS
)

/**
 * 2026.22.0: rd50.a(LinkMedia, Preview, msb0, boolean, String)
 * 2026.24.0: si60.a(LinkMedia, Preview, m4d0, boolean, String)
 *
 * The last parameter is the post url.
 */
internal object LinkMediaVideoUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Lcom/reddit/domain/model/LinkMedia;",
        "Lcom/reddit/domain/model/Preview;",
        "L",
        "Z",
        "Ljava/lang/String;"
    ),
    filters = PREVIEW_VIDEO_URL_FILTERS
)

/**
 * VideoUrls(defaultUrl) holds the url given to every Reddit video player.
 *
 * 2026.24.0: ibl0
 */
internal object VideoUrlsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    strings = listOf("VideoUrls(defaultUrl=")
)

internal object VideoUrlsConstructorFingerprint : Fingerprint(
    classFingerprint = VideoUrlsToStringFingerprint,
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Ljava/lang/String;")
)

/**
 * Main Link constructor, the only one that assigns the url field.
 */
internal object LinkConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "<init>",
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "Lcom/reddit/domain/model/Link;",
            name = "url",
            type = "Ljava/lang/String;"
        )
    )
)
