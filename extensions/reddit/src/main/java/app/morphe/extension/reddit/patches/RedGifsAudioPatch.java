/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * Media urls are not signed, but can change if the video is re-encoded or removed.
     */
    private static final long VIDEO_URL_CACHE_MILLISECONDS = 30 * 60 * 1000;

    /**
     * How long to wait before retrying a video that failed or has no audio.
     */
    private static final long NO_URL_CACHE_MILLISECONDS = 5 * 60 * 1000;

    private static final long TOKEN_CACHE_MILLISECONDS = 12 * 60 * 60 * 1000;

    private static final int MAX_CACHE_SIZE = 200;

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

    private static final Map<String, CachedUrl> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedUrl>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedUrl> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    private static final Set<String> pendingFetches = Collections.synchronizedSet(new HashSet<>());

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
     * Injection point.
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

            CachedUrl cached = cache.get(id);
            if (cached != null && !cached.isExpired()) {
                return cached.url;
            }

            if (Utils.isCurrentlyOnMainThread()) {
                // Network calls cannot be made on the main thread.
                // Use the original url this time, and the RedGifs url once it's fetched.
                fetchInBackground(id);
                return null;
            }

            return fetchAndCache(id);
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoUrl failure", ex);
            return null;
        }
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

    private static void fetchInBackground(String id) {
        if (!pendingFetches.add(id)) {
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                fetchAndCache(id);
            } finally {
                pendingFetches.remove(id);
            }
        });
    }

    @Nullable
    private static String fetchAndCache(String id) {
        String url = null;
        try {
            url = fetchVideoUrl(id);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to fetch RedGifs video: " + id, ex);
        }

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
            Logger.printDebug(() -> "RedGifs video: " + id + " response code: " + responseCode);
            return null;
        }

        JSONObject gif = Requester.parseJSONObject(connection).getJSONObject("gif");
        if (!gif.optBoolean("hasAudio", true)) {
            Logger.printDebug(() -> "RedGifs video has no audio: " + id);
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
