package app.vanishr.android;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import okhttp3.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class AppUpdates implements AutoCloseable {
    static final String ORIGIN = "https://vanishr-download.vercel.app";
    static final String FEED = ORIGIN + "/updates.json";
    static final int MAX_MANIFEST = 4096;
    static final long CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24);
    private static final Set<String> FIELDS = Set.of("schemaVersion", "versionCode", "versionName", "minSdk", "apkUrl", "sha256", "size");
    private static final Set<String> NUMBERS = Set.of("schemaVersion", "versionCode", "minSdk", "size");
    private final OkHttpClient client;

    record Release(int versionCode, String versionName, String apkUrl) { }

    AppUpdates() { this(new OkHttpClient()); }

    AppUpdates(OkHttpClient transport) {
        client = transport.newBuilder().connectionSpecs(List.of(ConnectionSpec.MODERN_TLS))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .cookieJar(CookieJar.NO_COOKIES).cache(null)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build();
    }

    Release check(long installedVersion, int androidVersion) throws IOException {
        Request request = new Request.Builder().url(FEED).header("Accept", "application/json")
                .header("Cache-Control", "no-store").get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (response.code() != 200 || body == null || body.contentLength() > MAX_MANIFEST
                    || body.contentType() == null || !body.contentType().type().equals("application")
                    || !body.contentType().subtype().equals("json")) throw new IOException("Update check unavailable");
            var source = body.source();
            source.request(MAX_MANIFEST + 1L);
            if (source.getBuffer().size() > MAX_MANIFEST) throw new IOException("Update metadata exceeds limit");
            return parse(source.getBuffer().readByteArray(), installedVersion, androidVersion);
        }
    }

    static Release parse(byte[] json, long installedVersion, int androidVersion) throws IOException {
        if (json.length == 0 || json.length > MAX_MANIFEST) throw new IOException("Invalid update metadata");
        try (JsonReader reader = new JsonReader(new StringReader(new String(json, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            Map<String, String> fields = new HashMap<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!FIELDS.contains(name) || fields.containsKey(name)) throw new IOException("Invalid update metadata");
                JsonToken expected = NUMBERS.contains(name) ? JsonToken.NUMBER : JsonToken.STRING;
                if (reader.peek() != expected) throw new IOException("Invalid update metadata");
                String value = reader.nextString();
                if (expected == JsonToken.NUMBER && !value.matches("[1-9][0-9]{0,9}")) throw new IOException("Invalid update metadata");
                fields.put(name, value);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !fields.keySet().equals(FIELDS)) throw new IOException("Invalid update metadata");
            long versionCode = Long.parseLong(fields.get("versionCode"));
            long minSdk = Long.parseLong(fields.get("minSdk"));
            long size = Long.parseLong(fields.get("size"));
            String versionName = fields.get("versionName");
            String apkUrl = fields.get("apkUrl");
            if (!fields.get("schemaVersion").equals("1") || versionCode > Integer.MAX_VALUE || minSdk < 28 || minSdk > 1000
                    || size > 95L * 1024 * 1024 || versionName.length() > 40
                    || !versionName.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                    || !fields.get("sha256").matches("[0-9a-f]{64}")
                    || !apkUrl.equals(ORIGIN + "/vanishr-" + versionName + ".apk")) throw new IOException("Invalid update metadata");
            if (versionCode <= installedVersion || minSdk > androidVersion) return null;
            return new Release((int) versionCode, versionName, apkUrl);
        } catch (IllegalStateException | IllegalArgumentException failure) {
            throw new IOException("Invalid update metadata");
        }
    }

    static boolean due(long now, long lastCheck) {
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= CHECK_INTERVAL;
    }

    static boolean shouldPrompt(Release release, long dismissedVersion, long dismissedAt, long now) {
        return release.versionCode() != dismissedVersion || due(now, dismissedAt);
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}