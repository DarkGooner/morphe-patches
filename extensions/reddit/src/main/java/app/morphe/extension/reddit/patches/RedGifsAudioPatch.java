/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.domain.image.model.ImageResolution;
import com.reddit.domain.model.Image;
import com.reddit.domain.model.Link;
import com.reddit.domain.model.Preview;
import com.reddit.domain.model.RedditVideo;
import com.reddit.domain.model.Variant;
import com.reddit.domain.model.Variants;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Reddit plays RedGifs posts using its own audio-less transcode
 * (preview.reddit_video_preview or the preview mp4 variant).
 * This replaces that url with the original RedGifs video, which includes audio.
 * <p>
 * The RedGifs media url is case-sensitive and is not part of the Reddit post data,
 * so it must be fetched from the RedGifs API. To have it ready for the first play,
 * the fetch starts as soon as a post is loaded.
 * <p>
 * Some video players are created only from Reddit's preview video url, without the post,
 * so the Reddit preview videos of each RedGifs post are recorded when the post is loaded,
 * and the url is replaced where every video player url is created.
 * <p>
 * The Reddit mute button is not changed, so the user can still mute.
 *
 * @see <a href="https://github.com/Redgifs/api/wiki">RedGifs API</a>
 */
@SuppressWarnings("unused")
public class RedGifsAudioPatch {

    private static final String API_URL = "https://api.redgifs.com/v2/";

    /**
     * Matches redgifs.com/watch/{id}, redgifs.com/ifr/{id} and i.redgifs.com/i/{id}.
     */
    private static final Pattern REDGIFS_ID_PATTERN = Pattern.compile(
            "^https?://(?:[a-z0-9-]+\\.)?redgifs\\.com/(?:watch|ifr|i)/([a-z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Matches the host and path of an url, without the query.
     */
    private static final Pattern URL_HOST_PATH_PATTERN = Pattern.compile(
            "^https?://([a-z0-9.-]+)/([^?#]*)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Temporary tokens only work with the user agent that requested them.
     */
    private static final String USER_AGENT = "Morphe";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    /**
     * How long a video url lookup off the main thread waits for the fetch to finish.
     * If it takes longer, the original silent video is used.
     */
    private static final long FETCH_WAIT_MILLISECONDS = 3000;

    /**
     * Prefix of the placeholder v.redd.it video id of posts without a Reddit hosted video.
     */
    private static final String SYNTHETIC_VIDEO_ID_PREFIX = "morpheredgifs";

    /**
     * How long the main thread waits for the RedGifs video of a placeholder url.
     * A placeholder url cannot be played, so it's better to wait than to fail.
     */
    private static final long SYNTHETIC_VIDEO_MAIN_THREAD_WAIT_MILLISECONDS = 2000;

    /**
     * Media urls are not signed, but can change if the video is re-encoded or removed.
     */
    private static final long VIDEO_URL_CACHE_MILLISECONDS = 30 * 60 * 1000;

    /**
     * How long to wait before retrying a video that could not be fetched.
     */
    private static final long NO_URL_CACHE_MILLISECONDS = 5 * 60 * 1000;

    /**
     * How long to remember a video that was deleted from RedGifs (HTTP 410).
     */
    private static final long DELETED_VIDEO_CACHE_MILLISECONDS = 24 * 60 * 60 * 1000;

    private static final long TOKEN_CACHE_MILLISECONDS = 12 * 60 * 60 * 1000;

    private static final int MAX_CACHE_SIZE = 500;

    private static final class CachedUrl {
        /**
         * Null if the video could not be fetched.
         */
        @Nullable
        final String url;
        final long expiresAt;

        CachedUrl(@Nullable String url, long cacheMilliseconds) {
            this.url = url;
            this.expiresAt = System.currentTimeMillis() + cacheMilliseconds;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private static <V> Map<String, V> createLruMap() {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    /**
     * RedGifs id to video url.
     */
    private static final Map<String, CachedUrl> cache = createLruMap();

    /**
     * Key of a Reddit preview video url (see {@link #getMediaKey(String)}) to RedGifs id.
     */
    private static final Map<String, String> mediaKeyToRedGifsId = createLruMap();

    private static final Map<String, FutureTask<String>> pendingFetches = new ConcurrentHashMap<>();

    private static final ExecutorService fetchExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "morphe-redgifs");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    private static String token;
    private static long tokenExpiresAt;

    private static boolean syntheticVideoUnsupportedLogged;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Called at the end of creating a post.
     * <p>
     * Records which Reddit preview videos belong to a RedGifs post, and starts fetching the
     * RedGifs video url.
     * <p>
     * Older RedGifs posts are embeds without a Reddit hosted preview video. Reddit shows these
     * as links that open in the browser. For these, a preview video is created with placeholder
     * urls that are replaced with the RedGifs video when played, so the post plays in the app.
     *
     * @return Preview with a video to set on the post, or null to keep the original preview.
     */
    @Nullable
    public static Preview onLinkCreated(@Nullable Link link) {
        try {
            if (link == null || !Settings.REDGIFS_AUDIO.get()) {
                return null;
            }

            String postUrl = link.getUrl();
            if (postUrl == null) {
                return null;
            }

            String id = getRedGifsId(postUrl);
            if (id == null) {
                return null;
            }

            Preview preview = link.getPreview();
            Preview syntheticPreview = null;
            if (!mapPreviewVideos(preview, id) && preview != null) {
                syntheticPreview = createPreviewWithVideo(preview, id);
            }

            if (getCachedUrl(id) == null) {
                startFetch(id);
            }

            return syntheticPreview;
        } catch (Throwable ex) {
            Logger.printException(() -> "onLinkCreated failure", ex);
            return null;
        }
    }

    /**
     * Injection point. Called when any video url is given to the video player.
     *
     * @param url Video url.
     * @return RedGifs video url with audio, or the original url.
     */
    public static String getPlaybackUrl(String url) {
        try {
            if (url == null || !Settings.REDGIFS_AUDIO.get()) {
                return url;
            }

            String key = getMediaKey(url);
            if (key == null) {
                return url;
            }

            String redGifsUrl;
            final String syntheticPrefix = "v.redd.it/" + SYNTHETIC_VIDEO_ID_PREFIX;
            if (key.startsWith(syntheticPrefix)) {
                // Placeholder url of a post without a Reddit hosted video.
                String id = key.substring(syntheticPrefix.length());
                redGifsUrl = resolveVideoUrl(id, SYNTHETIC_VIDEO_MAIN_THREAD_WAIT_MILLISECONDS);
            } else {
                String id = mediaKeyToRedGifsId.get(key);
                if (id == null) {
                    return url;
                }
                redGifsUrl = resolveVideoUrl(id, 0);
            }

            return redGifsUrl != null ? redGifsUrl : url;
        } catch (Exception ex) {
            Logger.printException(() -> "getPlaybackUrl failure", ex);
            return url;
        }
    }

    /**
     * Injection point. Post detail and full screen videos.
     *
     * @param postUrl Url of the post.
     * @return RedGifs video url with audio, or null to use the original url.
     */
    @Nullable
    public static String getVideoUrl(@Nullable String postUrl) {
        try {
            if (postUrl == null || !Settings.REDGIFS_AUDIO.get()) {
                return null;
            }

            String id = getRedGifsId(postUrl);
            if (id == null) {
                return null;
            }

            return resolveVideoUrl(id, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoUrl failure", ex);
            return null;
        }
    }

    /**
     * Records the Reddit preview videos of a post.
     *
     * @return If the post has a Reddit hosted preview video.
     */
    private static boolean mapPreviewVideos(@Nullable Preview preview, String id) {
        boolean hasRedditVideo = false;
        if (preview != null) {
            RedditVideo video = preview.getRedditVideoPreview();
            if (video != null) {
                hasRedditVideo |= mapMediaUrl(video.getDashUrl(), id);
                hasRedditVideo |= mapMediaUrl(video.getHlsUrl(), id);
                hasRedditVideo |= mapMediaUrl(video.getFallBackUrl(), id);
            }

            List<Image> images = preview.getImages();
            if (images != null) {
                for (Image image : images) {
                    Variants variants = image.getVariants();
                    Variant mp4 = variants == null ? null : variants.getMp4();
                    if (mp4 == null) {
                        continue;
                    }

                    ImageResolution source = mp4.getSource();
                    if (source != null) {
                        hasRedditVideo |= mapMediaUrl(source.getUrl(), id);
                    }
                    List<ImageResolution> resolutions = mp4.getResolutions();
                    if (resolutions != null) {
                        for (ImageResolution resolution : resolutions) {
                            hasRedditVideo |= mapMediaUrl(resolution.getUrl(), id);
                        }
                    }
                }
            }
        }
        return hasRedditVideo;
    }

    /**
     * @return If the url is a Reddit hosted video.
     */
    private static boolean mapMediaUrl(@Nullable String url, String id) {
        String key = getMediaKey(url);
        if (key == null) {
            return false;
        }
        mediaKeyToRedGifsId.put(key, id);
        return true;
    }

    /**
     * Creates a copy of the preview with a preview video, the same as newer RedGifs posts have.
     *
     * @return The preview with a video, or null if it can't be created.
     */
    @Nullable
    private static Preview createPreviewWithVideo(Preview preview, String id) {
        List<Image> images = preview.getImages();
        if (images == null || images.isEmpty()) {
            // Reddit expects video posts to have a preview image.
            return null;
        }

        int width = 0;
        int height = 0;
        ImageResolution source = images.get(0).getSource();
        if (source != null) {
            width = source.getWidth();
            height = source.getHeight();
        }

        String baseUrl = "https://v.redd.it/" + SYNTHETIC_VIDEO_ID_PREFIX + id;
        try {
            RedditVideo video = new RedditVideo(
                    null,
                    baseUrl + "/DASHPlaylist.mpd",
                    0,
                    baseUrl + "/CMAF_480.mp4",
                    height,
                    width,
                    baseUrl + "/HLSPlaylist.m3u8",
                    true,
                    "",
                    "completed",
                    null,
                    null
            );
            Logger.printDebug(() -> "Created preview video for RedGifs post without a Reddit video: " + id);
            return new Preview(images, video);
        } catch (Throwable ex) {
            // The constructors differ between Reddit versions.
            if (!syntheticVideoUnsupportedLogged) {
                syntheticVideoUnsupportedLogged = true;
                Logger.printException(() -> "Cannot create a preview video in this Reddit version", ex);
            }
            return null;
        }
    }

    /**
     * Reddit video urls of the same video differ in format and query parameters
     * (DASH, HLS, fallback mp4, packaged mp4), but share the v.redd.it video id.
     *
     * @return Key identifying the Reddit hosted video of an url, or null if it's not hosted by Reddit.
     */
    @Nullable
    private static String getMediaKey(@Nullable String url) {
        if (url == null) {
            return null;
        }

        Matcher matcher = URL_HOST_PATH_PATTERN.matcher(url);
        if (!matcher.find()) {
            return null;
        }

        //noinspection DataFlowIssue
        String host = matcher.group(1).toLowerCase(Locale.US);
        String path = matcher.group(2);
        if (!host.endsWith("redd.it")) {
            return null;
        }

        if (host.equals("v.redd.it") || host.equals("packaged-media.redd.it")) {
            //noinspection DataFlowIssue
            int slash = path.indexOf('/');
            return "v.redd.it/" + (slash < 0 ? path : path.substring(0, slash));
        }

        return host + "/" + path;
    }

    @Nullable
    private static String getRedGifsId(String postUrl) {
        Matcher matcher = REDGIFS_ID_PATTERN.matcher(postUrl);
        if (!matcher.find()) {
            return null;
        }
        //noinspection DataFlowIssue
        return matcher.group(1).toLowerCase(Locale.US);
    }

    /**
     * @param mainThreadWaitMilliseconds How long to wait for the fetch on the main thread.
     * @return RedGifs video url, or null if it's not available.
     */
    @Nullable
    private static String resolveVideoUrl(String id, long mainThreadWaitMilliseconds) throws Exception {
        CachedUrl cached = getCachedUrl(id);
        if (cached != null) {
            return cached.url;
        }

        FutureTask<String> fetch = startFetch(id);

        final boolean mainThread = Utils.isCurrentlyOnMainThread();
        if (mainThread && mainThreadWaitMilliseconds <= 0) {
            // Do not block the main thread.
            // Use the original url this time, and the RedGifs url once it's fetched.
            Logger.printDebug(() -> "RedGifs video not fetched yet: " + id);
            return null;
        }

        try {
            return fetch.get(mainThread ? mainThreadWaitMilliseconds : FETCH_WAIT_MILLISECONDS,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printDebug(() -> "Timed out waiting for RedGifs video: " + id);
            return null;
        }
    }

    @Nullable
    private static CachedUrl getCachedUrl(String id) {
        CachedUrl cached = cache.get(id);
        return cached == null || cached.isExpired() ? null : cached;
    }

    private static FutureTask<String> startFetch(String id) {
        FutureTask<String> existing = pendingFetches.get(id);
        if (existing != null) {
            return existing;
        }

        FutureTask<String> fetch = new FutureTask<>(() -> {
            try {
                return fetchAndCache(id);
            } finally {
                pendingFetches.remove(id);
            }
        });

        existing = pendingFetches.putIfAbsent(id, fetch);
        if (existing != null) {
            return existing;
        }

        fetchExecutor.execute(fetch);
        return fetch;
    }

    @Nullable
    private static String fetchAndCache(String id) {
        CachedUrl result;
        try {
            result = fetchVideoUrl(id);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to fetch RedGifs video: " + id, ex);
            result = new CachedUrl(null, NO_URL_CACHE_MILLISECONDS);
        }

        cache.put(id, result);
        return result.url;
    }

    private static CachedUrl fetchVideoUrl(String id) throws Exception {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = openGifConnection(id, getToken(false));
        if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect();
            connection = openGifConnection(id, getToken(true));
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            Logger.printDebug(() -> "RedGifs video: " + id + " response code: " + responseCode);

            // Videos deleted from RedGifs do not come back.
            return new CachedUrl(null, responseCode == HttpURLConnection.HTTP_GONE
                    ? DELETED_VIDEO_CACHE_MILLISECONDS
                    : NO_URL_CACHE_MILLISECONDS);
        }

        // Videos without audio are also used, because posts without a Reddit hosted video
        // play only the RedGifs video.
        JSONObject gif = Requester.parseJSONObject(connection).getJSONObject("gif");

        // Prefer sd. The hd file is often 1080p at 3-5 Mbps, in a single non-adaptive file,
        // sometimes with the index at the end. That causes playback to pause and resume while
        // buffering. The sd file is about 5x smaller, has the same audio and has the index first.
        JSONObject urls = gif.getJSONObject("urls");
        String url = urls.optString("sd");
        if (url.isEmpty()) {
            url = urls.optString("hd");
        }

        return url.isEmpty()
                ? new CachedUrl(null, NO_URL_CACHE_MILLISECONDS)
                : new CachedUrl(url, VIDEO_URL_CACHE_MILLISECONDS);
    }

    private static HttpURLConnection openGifConnection(String id, String bearerToken) throws Exception {
        HttpURLConnection connection = openConnection(API_URL + "gifs/" + id);
        connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
        return connection;
    }

    private static synchronized String getToken(boolean forceRefresh) throws Exception {
        if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpiresAt) {
            return token;
        }

        HttpURLConnection connection = openConnection(API_URL + "auth/temporary");
        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            throw new IllegalStateException("RedGifs auth response code: " + responseCode);
        }

        token = Requester.parseJSONObject(connection).getString("token");
        tokenExpiresAt = System.currentTimeMillis() + TOKEN_CACHE_MILLISECONDS;
        return token;
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }
}
