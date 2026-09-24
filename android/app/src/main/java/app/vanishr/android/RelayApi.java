package app.vanishr.android;

import com.google.gson.*;
import com.google.gson.stream.*;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class RelayApi implements AutoCloseable {
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).registerTypeAdapter(byte[].class, new TypeAdapter<byte[]>() {
        @Override public void write(JsonWriter writer, byte[] value) throws IOException {
            if (value == null) writer.nullValue(); else writer.value(Base64.getEncoder().encodeToString(value));
        }
        @Override public byte[] read(JsonReader reader) throws IOException {
            if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
            try { return Base64.getDecoder().decode(reader.nextString()); }
            catch (IllegalArgumentException failure) { throw new IOException("Invalid binary encoding"); }
        }
    }).create();
    static final class ApiFailure extends IOException {
        final int status;
        final String code;
        ApiFailure(int status) { this(status, ""); }
        ApiFailure(int status, String code) {
            super("Relay request failed");
            this.status = status;
                this.code = Set.of("device_already_registered", "device_unavailable", "account_unavailable", "google_sign_in_unavailable", "prekeys_unavailable",
                    "group_full","group_capacity","group_changed","group_not_ready","group_identity_changed","group_owner_required","owner_must_close_group",
                    "photo_peer_offline", "photo_session_ended", "photo_transfer_busy", "photo_identity_changed", "admin_required").contains(code) ? code : "";
        }
        String userMessage() {
            if (code.equals("photo_peer_offline")) return "The other phone is offline. Ask them to open Vanishr.";
            if (code.equals("photo_session_ended")) return "Photo access ended. A new approval is required.";
            if (code.equals("photo_identity_changed")) return "This contact's identity changed. Verify it again.";
            if (code.equals("admin_required")) return "Only administrators can request photo access.";
            if (code.equals("group_full")) return "This group has reached its 200-member limit.";
            if (code.equals("group_capacity")) return "An account can have up to 20 active groups and invitations.";
            if (code.equals("group_changed")) return "Group membership changed. Refresh and try again.";
            if (code.equals("group_not_ready")) return "Wait for another member and verified group keys.";
            if (code.equals("group_identity_changed")) return "A group member's device identity changed. Verify them again before reinviting.";
            if (code.equals("group_owner_required")) return "Only the group owner can change membership.";
            if (code.equals("owner_must_close_group")) return "The owner must close the group before leaving.";
            if (status == 429) return "Too many requests. Wait a moment before trying again.";
            if (status == 401) return "Sign-in failed or your session expired. Sign in again.";
            if (status == 409 && code.equals("device_already_registered")) return "This account has a different registered device. Replace it only if you intend to move the account here.";
            if (status == 409 && code.equals("account_unavailable")) return "That username is already taken. Choose another username.";
            if (status == 409 && code.equals("prekeys_unavailable")) return "The contact needs to sign in before a new encrypted conversation can start.";
            if (status == 404) return "The requested account or item was not found.";
            if (status >= 500) return "The service is temporarily unavailable. Try again shortly.";
            return "The request could not be completed. Try again.";
        }
    }

    static ApiFailure failure(int status, byte[] body) {
        String code = "";
        try {
            if (body.length <= 4096) {
                JsonObject error = JSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                if (error != null && error.has("error") && error.get("error").isJsonPrimitive() && error.getAsJsonPrimitive("error").isString())
                    code = error.get("error").getAsString();
            }
        } catch (RuntimeException ignored) { }
        return new ApiFailure(status, code);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectionSpecs(Collections.singletonList(ConnectionSpec.MODERN_TLS)).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build();
    private final HttpUrl origin;
    private volatile String token;
    @FunctionalInterface interface SessionRefresh { void refresh(boolean rejected) throws Exception; }
    private SessionRefresh sessionRefresh;

    RelayApi(String baseUrl, String token) {
        origin = HttpUrl.get(baseUrl);
        if (!origin.isHttps() || !origin.username().isEmpty() || !origin.password().isEmpty()
                || !origin.encodedPath().equals("/") || origin.query() != null || origin.fragment() != null)
            throw new IllegalArgumentException("A trusted HTTPS origin is required");
        this.token = token;
    }

    String origin() { return origin.toString(); }
    void token(String token) { this.token = token; }
    void sessionRefresh(SessionRefresh refresh) { sessionRefresh = refresh; }

    private Request.Builder request(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid relay path");
        Request.Builder request = new Request.Builder().url(Objects.requireNonNull(origin.resolve(path))).header("Cache-Control", "no-store");
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private Request authorize(Request request) {
        Request.Builder builder = request.newBuilder().removeHeader("Authorization");
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }

    private byte[] execute(Request request, int maximum) throws Exception {
        boolean renewal = request.url().encodedPath().equals("/auth/refresh");
        if (renewal) request = request.newBuilder().removeHeader("Authorization").build();
        else if (sessionRefresh != null) { sessionRefresh.refresh(false); request = authorize(request); }
        try { return executeOnce(request, maximum); }
        catch (ApiFailure failure) {
            if (failure.status != 401 || renewal || sessionRefresh == null) throw failure;
            sessionRefresh.refresh(true);
            return executeOnce(authorize(request), maximum);
        }
    }

    private byte[] executeOnce(Request request, int maximum) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                byte[] error = new byte[0];
                try { if (response.body() != null) error = AndroidVault.boundedRead(response.body().byteStream(), 4096); }
                catch (IOException ignored) { }
                try { throw failure(response.code(), error); }
                finally { Arrays.fill(error, (byte) 0); }
            }
            if (response.body() == null) return new byte[0];
            if (response.body().contentLength() > maximum) throw new IOException("Response size limit exceeded");
            return AndroidVault.boundedRead(response.body().byteStream(), maximum);
        }
    }

    <Result> Result call(String method, String path, Object body, Class<Result> type) throws Exception {
        RequestBody content = body == null ? null : RequestBody.create(JSON.toJson(body).getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"));
        if ((method.equals("POST") || method.equals("PUT")) && content == null) content = RequestBody.create(new byte[0], null);
        byte[] response = execute(request(path).method(method, content).build(), 8 * 1024 * 1024);
        if (type == Void.class || response.length == 0) return null;
        try { return JSON.fromJson(new String(response, StandardCharsets.UTF_8), type); }
        catch (JsonParseException failure) { throw new IOException("Invalid relay response"); }
    }

    void upload(UUID id, UUID recipientId, UUID recipientDeviceId, long expiresAt, byte[] ciphertext) throws Exception {
        String path = "/media/" + id + "?recipientId=" + recipientId + "&recipientDeviceId=" + recipientDeviceId + "&expiresAt=" + expiresAt;
        execute(request(path).put(RequestBody.create(ciphertext, MediaType.get("application/octet-stream"))).build(), 4096);
    }

    byte[] download(UUID id) throws Exception { return execute(request("/media/" + id).get().build(), app.vanishr.crypto.ImageCipher.MAX_IMAGE_BYTES + 16); }

    byte[] groupMedia(UUID group,UUID message) throws Exception { return execute(request("/groups/"+group+"/messages/"+message+"/media").get().build(),app.vanishr.crypto.ImageCipher.MAX_IMAGE_BYTES+16); }

    ContactPresence.Status[] presence(ContactPresence.Update update) throws Exception {
        if (sessionRefresh != null) sessionRefresh.refresh(false);
        RequestBody body = RequestBody.create(JSON.toJson(update).getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"));
        Call call = client.newCall(request("/presence").post(body).build());
        call.timeout().timeout(3, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new ApiFailure(response.code());
            if (response.body() == null) throw new IOException("Missing presence response");
            byte[] bytes = AndroidVault.boundedRead(response.body().byteStream(), 65_536);
            try { return JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), ContactPresence.Status[].class); }
            catch (JsonParseException failure) { throw new IOException("Invalid presence response"); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    <Result> Result photoCall(String method, String path, Object body, Class<Result> type) throws Exception {
        RequestBody content = body == null ? null : RequestBody.create(JSON.toJson(body).getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"));
        Call call = client.newCall(request(path).method(method, content).build());
        call.timeout().timeout(5, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (response.body() == null) throw new IOException("Photo service unavailable");
            byte[] bytes = AndroidVault.boundedRead(response.body().byteStream(), response.isSuccessful() ? 100_000 : 4096);
            try {
                if (!response.isSuccessful()) throw failure(response.code(), bytes);
                return JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    WebSocket events(Runnable wake, java.util.function.Consumer<Boolean> state) { return events("/events", client, wake, state); }
    WebSocket photoEvents(Runnable wake, java.util.function.Consumer<Boolean> state) {
        return events("/photo-events", client.newBuilder().pingInterval(10, TimeUnit.SECONDS).build(), wake, state);
    }
    private WebSocket events(String path, OkHttpClient transport, Runnable wake, java.util.function.Consumer<Boolean> state) {
        return transport.newWebSocket(request(path).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) { state.accept(true); wake.run(); }
            @Override public void onMessage(WebSocket socket, String text) { if (text.equals("{\"event\":\"new_message\"}")) wake.run(); }
            @Override public void onClosing(WebSocket socket, int code, String reason) { state.accept(false); socket.close(code, null); }
            @Override public void onClosed(WebSocket socket, int code, String reason) { state.accept(false); }
            @Override public void onFailure(WebSocket socket, Throwable failure, Response response) { state.accept(false); }
        });
    }

    @Override public void close() {
        token = null;
        sessionRefresh = null;
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}