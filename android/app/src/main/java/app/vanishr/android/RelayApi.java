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
            this.code = Set.of("device_already_registered", "device_unavailable", "account_unavailable", "google_sign_in_unavailable", "prekeys_unavailable").contains(code) ? code : "";
        }
        String userMessage() {
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
    private String token;

    RelayApi(String baseUrl, String token) {
        origin = HttpUrl.get(baseUrl);
        if (!origin.isHttps() || !origin.username().isEmpty() || !origin.password().isEmpty()
                || !origin.encodedPath().equals("/") || origin.query() != null || origin.fragment() != null)
            throw new IllegalArgumentException("A trusted HTTPS origin is required");
        this.token = token;
    }

    String origin() { return origin.toString(); }
    void token(String token) { this.token = token; }

    private Request.Builder request(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid relay path");
        Request.Builder request = new Request.Builder().url(Objects.requireNonNull(origin.resolve(path))).header("Cache-Control", "no-store");
        if (token != null && !token.isEmpty()) request.header("Authorization", "Bearer " + token);
        return request;
    }

    private byte[] execute(Request request, int maximum) throws IOException {
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

    <Result> Result call(String method, String path, Object body, Class<Result> type) throws IOException {
        RequestBody content = body == null ? null : RequestBody.create(JSON.toJson(body).getBytes(StandardCharsets.UTF_8), MediaType.get("application/json"));
        if ((method.equals("POST") || method.equals("PUT")) && content == null) content = RequestBody.create(new byte[0], null);
        byte[] response = execute(request(path).method(method, content).build(), 8 * 1024 * 1024);
        if (type == Void.class || response.length == 0) return null;
        try { return JSON.fromJson(new String(response, StandardCharsets.UTF_8), type); }
        catch (JsonParseException failure) { throw new IOException("Invalid relay response"); }
    }

    void upload(UUID id, UUID recipientId, UUID recipientDeviceId, long expiresAt, byte[] ciphertext) throws IOException {
        String path = "/media/" + id + "?recipientId=" + recipientId + "&recipientDeviceId=" + recipientDeviceId + "&expiresAt=" + expiresAt;
        execute(request(path).put(RequestBody.create(ciphertext, MediaType.get("application/octet-stream"))).build(), 4096);
    }

    byte[] download(UUID id) throws IOException { return execute(request("/media/" + id).get().build(), app.vanishr.crypto.ImageCipher.MAX_IMAGE_BYTES + 16); }

    WebSocket events(Runnable wake) {
        return client.newWebSocket(request("/events").build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) { if (text.equals("{\"event\":\"new_message\"}")) wake.run(); }
        });
    }

    @Override public void close() {
        token = null;
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}