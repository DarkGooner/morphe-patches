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
 * the fetch starts as soon as a post is loaded, and the video url lookup waits for it
 * when called off the main thread.
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
     * Media urls are not signed, but can change if the video is re-encoded or removed.
     */
    private static final long VIDEO_URL_CACHE_MILLISECONDS = 30 * 60 * 1000;

    /**
     * How long to wait before retrying a video that failed or has no audio.
     */
    private static final long NO_URL_CACHE_MILLISECONDS = 5 * 60 * 1000;

    private static final long TOKEN_CACHE_MILLISECONDS = 12 * 60 * 60 * 1000;

    private static final int MAX_CACHE_SIZE = 500;

    private static final class CachedUrl {
        /**
         * Null if the video has no audio or could not be fetched.
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

    /**
     * Matches the host and path of an url, without the query.
     */
    private static final Pattern URL_HOST_PATH_PATTERN = Pattern.compile(
            "^https?://([a-z0-9.-]+)/([^?#]*)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Map<String, FutureTask<String>> pendingFetches = new ConcurrentHashMap<>();

    private static final ExecutorService fetchExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "morphe-redgifs");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    private static String token;
    private static long tokenExpiresAt;

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Called when a post is created.
     * Records which Reddit preview videos belong to a RedGifs post, and starts fetching the
     * RedGifs video url.
     */
    public static void prefetch(@Nullable Link link) {
        try {
            if (link == null || !Settings.REDGIFS_AUDIO.get()) {
                return;
            }

            String postUrl = link.getUrl();
            if (postUrl == null) {
                return;
            }

            String id = getRedGifsId(postUrl);
            if (id == null) {
                return;
            }

            Preview preview = link.getPreview();
            if (preview != null) {
                RedditVideo video = preview.getRedditVideoPreview();
                if (video != null) {
                    mapMediaUrl(video.getDashUrl(), id);
                    mapMediaUrl(video.getHlsUrl(), id);
                    mapMediaUrl(video.getFallBackUrl(), id);
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
                            mapMediaUrl(source.getUrl(), id);
                        }
                        List<ImageResolution> resolutions = mp4.getResolutions();
                        if (resolutions != null) {
                            for (ImageResolution resolution : resolutions) {
                                mapMediaUrl(resolution.getUrl(), id);
                            }
                        }
                    }
                }
            }

            if (getCachedUrl(id) == null) {
                startFetch(id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "prefetch failure", ex);
        }
    }

    private static void mapMediaUrl(@Nullable String url, String id) {
        String key = getMediaKey(url);
        if (key != null && mediaKeyToRedGifsId.put(key, id) == null) {
            diagnostic(() -> "map key=" + key + " id=" + id + " thread=" + threadName());
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

            String id = mediaKeyToRedGifsId.get(key);
            if (id == null) {
                diagnostic(() -> "playback unmapped key=" + key + " thread=" + threadName());
                return url;
            }

            String redGifsUrl = resolveVideoUrl(id, "VideoUrls key=" + key);
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

            return resolveVideoUrl(id, caller());
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoUrl failure", ex);
            return null;
        }
    }

    /**
     * @return RedGifs video url, or null if it's not available.
     */
    @Nullable
    private static String resolveVideoUrl(String id, String source) throws Exception {
        final String thread = threadName();

        CachedUrl cached = getCachedUrl(id);
        if (cached != null) {
            diagnostic(() -> "resolve id=" + id + " source=" + source + " thread=" + thread
                    + " cache=HIT result=" + describe(cached.url));
            return cached.url;
        }

        FutureTask<String> fetch = startFetch(id);

        if (Utils.isCurrentlyOnMainThread()) {
            // Cannot wait on the main thread.
            // Use the original url this time, and the RedGifs url once it's fetched.
            diagnostic(() -> "resolve id=" + id + " source=" + source + " thread=" + thread
                    + " cache=MISS result=SILENT(main thread, not waiting)");
            return null;
        }

        final long start = System.currentTimeMillis();
        String url;
        String outcome;
        try {
            url = fetch.get(FETCH_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS);
            outcome = "waited";
        } catch (TimeoutException ex) {
            url = null;
            outcome = "TIMEOUT";
        }
        final String result = describe(url);
        final String waitOutcome = outcome;
        final long waitMs = System.currentTimeMillis() - start;
        diagnostic(() -> "resolve id=" + id + " source=" + source + " thread=" + thread
                + " cache=MISS " + waitOutcome + "=" + waitMs + "ms result=" + result);
        return url;
    }

    // TODO: Remove the diagnostic logging once RedGifs audio works consistently.
    private static void diagnostic(Logger.LogMessage message) {
        Logger.printInfo(() -> "RG-DIAG " + message.buildMessageString());
    }

    private static String threadName() {
        return Utils.isCurrentlyOnMainThread() ? "MAIN" : Thread.currentThread().getName();
    }

    /**
     * @return The first app method on the stack that is not part of this class.
     */
    private static String caller() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            if (!className.startsWith("java.") && !className.startsWith("dalvik.")
                    && !className.equals(RedGifsAudioPatch.class.getName())) {
                return className + "." + element.getMethodName();
            }
        }
        return "unknown";
    }

    private static String describe(@Nullable String url) {
        return url == null ? "SILENT(original)" : "REDGIFS";
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
        final long start = System.currentTimeMillis();
        String url = null;
        try {
            url = fetchVideoUrl(id);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to fetch RedGifs video: " + id, ex);
        }
        final String result = url == null ? "NO_URL" : "OK";
        final long fetchMs = System.currentTimeMillis() - start;
        diagnostic(() -> "fetch id=" + id + " result=" + result + " took=" + fetchMs + "ms");

        cache.put(id, url == null
                ? new CachedUrl(null, NO_URL_CACHE_MILLISECONDS)
                : new CachedUrl(url, VIDEO_URL_CACHE_MILLISECONDS));
        return url;
    }

    @Nullable
    private static String fetchVideoUrl(String id) throws Exception {
        Utils.verifyOffMainThread();

        HttpURLConnection connection = openGifConnection(id, getToken(false));
        if (connection.getResponseCode() == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect();
            connection = openGifConnection(id, getToken(true));
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            diagnostic(() -> "fetch id=" + id + " response code: " + responseCode);
            return null;
        }

        JSONObject gif = Requester.parseJSONObject(connection).getJSONObject("gif");
        if (!gif.optBoolean("hasAudio", true)) {
            diagnostic(() -> "fetch id=" + id + " hasAudio=false");
            return null;
        }

        JSONObject urls = gif.getJSONObject("urls");
        String url = urls.optString("hd");
        if (url.isEmpty()) {
            url = urls.optString("sd");
        }
        return url.isEmpty() ? null : url;
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
