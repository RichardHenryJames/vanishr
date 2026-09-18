package app.vanishr.android;

import com.google.gson.JsonObject;
import okhttp3.*;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AppUpdatesTest {
    private JsonObject manifest() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("versionCode", 10);
        json.addProperty("versionName", "0.2.7");
        json.addProperty("minSdk", 28);
        json.addProperty("apkUrl", AppUpdates.ORIGIN + "/vanishr-0.2.7.apk");
        json.addProperty("sha256", "a".repeat(64));
        json.addProperty("size", 87_500_000);
        return json;
    }

    private byte[] bytes(JsonObject json) { return json.toString().getBytes(StandardCharsets.UTF_8); }

    @Test public void onlyNewerCompatibleReleasesAreOffered() throws Exception {
        AppUpdates.Release release = AppUpdates.parse(bytes(manifest()), 9, 28);
        assertNotNull(release);
        assertEquals(10, release.versionCode());
        assertEquals("0.2.7", release.versionName());
        assertEquals(AppUpdates.ORIGIN + "/vanishr-0.2.7.apk", release.apkUrl());
        assertNull(AppUpdates.parse(bytes(manifest()), 10, 36));
        assertNull(AppUpdates.parse(bytes(manifest()), 11, 36));
        assertNull(AppUpdates.parse(bytes(manifest()), 9, 27));
    }

    @Test public void onlyTheExactVersionedHttpsDownloadIsAccepted() {
        for (String url : List.of("http://vanishr-download.vercel.app/vanishr-0.2.7.apk", "https://example.org/vanishr-0.2.7.apk",
                "https://vanishr-download.vercel.app.example.org/vanishr-0.2.7.apk", AppUpdates.ORIGIN + "/vanishr-0.2.6.apk",
                AppUpdates.ORIGIN + "/vanishr-0.2.7.apk?token=anything", AppUpdates.ORIGIN + "/vanishr-0.2.7.apk#fragment",
                "https://user@vanishr-download.vercel.app/vanishr-0.2.7.apk", AppUpdates.ORIGIN + ":444/vanishr-0.2.7.apk",
                AppUpdates.ORIGIN + "/other/../vanishr-0.2.7.apk", "javascript:alert(1)", "file:///data/local/tmp/app.apk")) {
            JsonObject json = manifest(); json.addProperty("apkUrl", url);
            assertThrows(IOException.class, () -> AppUpdates.parse(bytes(json), 9, 36));
        }
    }

    @Test public void malformedDuplicateUnknownAndOversizedMetadataIsRejected() {
        String valid = manifest().toString();
        for (String json : List.of("null", "[]", "{}", valid + "{}", valid.replace("\"versionCode\":10", "\"versionCode\":10,\"versionCode\":11"),
                valid.replace("\"versionCode\":10", "\"versionCode\":\"10\""), valid.replace("\"versionCode\":10", "\"versionCode\":10.1"),
                valid.replace("\"versionCode\":10", "\"versionCode\":2147483648"), valid.replace("\"versionCode\":10", "\"versionCode\":-1"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), valid.replace("\"size\":87500000", "\"size\":0"),
                valid.replace("\"size\":87500000", "\"size\":100000000"), valid.replace("\"minSdk\":28", "\"minSdk\":0"),
                valid.replace("\"sha256\":\"" + "a".repeat(64) + "\"", "\"sha256\":null"),
                valid.replace("0.2.7", "../apk"), valid.replace("\"versionCode\":10", "\"script\":\"code\",\"versionCode\":10"),
                " ".repeat(AppUpdates.MAX_MANIFEST) + valid)) {
            assertThrows(IOException.class, () -> AppUpdates.parse(json.getBytes(StandardCharsets.UTF_8), 9, 36));
        }
    }

    @Test public void checksAndDismissalsHaveABoundedCooldown() {
        long now = 1_800_000_000_000L;
        assertTrue(AppUpdates.due(now, 0));
        assertFalse(AppUpdates.due(now, now - 1000));
        assertTrue(AppUpdates.due(now, now - AppUpdates.CHECK_INTERVAL));
        assertTrue(AppUpdates.due(now, now + 1000));
        AppUpdates.Release release = new AppUpdates.Release(10, "0.2.7", AppUpdates.ORIGIN + "/vanishr-0.2.7.apk");
        assertFalse(AppUpdates.shouldPrompt(release, 10, now - 1000, now));
        assertTrue(AppUpdates.shouldPrompt(release, 9, now - 1000, now));
        assertTrue(AppUpdates.shouldPrompt(release, 10, now - AppUpdates.CHECK_INTERVAL, now));
    }

    @Test public void metadataRequestHasNoAccountDataOrRedirectFollowing() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertEquals(AppUpdates.FEED, chain.request().url().toString());
            assertEquals("GET", chain.request().method());
            assertNull(chain.request().header("Authorization"));
            assertNull(chain.request().header("Cookie"));
            assertNull(chain.request().url().query());
            assertNull(chain.request().body());
            assertEquals("no-store", chain.request().header("Cache-Control"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", "https://example.org/updates.json").body(ResponseBody.create(new byte[0], MediaType.get("application/json"))).build();
        }).build();
        try (AppUpdates updates = new AppUpdates(transport)) {
            assertThrows(IOException.class, () -> updates.check(9, 36));
            assertEquals(1, requests.get());
        }
    }

    @Test public void networkResponsesAreBoundedAndOfflineIsNotAnUpdate() throws Exception {
        for (String body : List.of(manifest().toString(), " ".repeat(AppUpdates.MAX_MANIFEST + 1))) {
            OkHttpClient transport = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(body.getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"))).build()).build();
            try (AppUpdates updates = new AppUpdates(transport)) {
                if (body.length() <= AppUpdates.MAX_MANIFEST) assertNotNull(updates.check(9, 36));
                else assertThrows(IOException.class, () -> updates.check(9, 36));
            }
        }
        OkHttpClient offline = new OkHttpClient.Builder().addInterceptor(chain -> { throw new IOException("Offline"); }).build();
        try (AppUpdates updates = new AppUpdates(offline)) { assertThrows(IOException.class, () -> updates.check(9, 36)); }
    }
}