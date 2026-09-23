package app.vanishr.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import static app.vanishr.relay.RelayTypes.*;

@RestController
public class RelayController {
    public record PushToken(@NotNull @Size(min = 20, max = 4096) String token, boolean routeHints) {
        public PushToken(String token) { this(token, false); }
        @Override public String toString() { return "PushToken[redacted]"; }
    }
    public record NotificationReference(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String reference) {
        @Override public String toString() { return "NotificationReference[redacted]"; }
    }
    private final AuthService auth;
    private final AccountDirectory accounts;
    private final RedisRelay relay;
    private final RateLimiter rates;
    private final GenericNotifier notifications;
    private final Presence presence;

    public RelayController(AuthService auth, AccountDirectory accounts, RedisRelay relay, RateLimiter rates, GenericNotifier notifications, Presence presence) {
        this.auth = auth;
        this.accounts = accounts;
        this.relay = relay;
        this.rates = rates;
        this.notifications = notifications;
        this.presence = presence;
    }

    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "up"); }

    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Token register(@Valid @RequestBody AuthService.Login request) { return auth.register(request); }

    @PostMapping("/auth/login") public AuthService.Token login(@Valid @RequestBody AuthService.Login request) {
        rates.require("login:" + RedisRelay.digest(request.handle().getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 5, 60);
        return auth.login(request);
    }

    @PostMapping("/auth/refresh") public AuthService.Token refresh(@Valid @RequestBody AuthService.Refresh request) {
        rates.require("renew:" + RedisRelay.digest(request.refreshToken().getBytes(java.nio.charset.StandardCharsets.US_ASCII)), 6, 60);
        return auth.refresh(request);
    }

    @GetMapping("/auth/me") public Actor me(@AuthenticationPrincipal Actor actor) { return actor; }

    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String authorization) {
        auth.revoke(authorization.substring(7));
        if (actor.deviceId() != null) {
            presence.clear(actor.deviceId());
            notifications.unregister(actor.deviceId());
        }
    }

    @PostMapping("/devices") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Token device(@AuthenticationPrincipal Actor actor, @RequestHeader("Authorization") String authorization,
                                    @Valid @RequestBody AccountDirectory.DeviceRequest request) {
        accounts.registerDevice(actor.userId(), request);
        auth.revoke(authorization.substring(7));
        return auth.issue(new Actor(actor.userId(), request.deviceId()));
    }

    @PostMapping("/devices/push") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void push(@AuthenticationPrincipal Actor actor, @Valid @RequestBody PushToken request) { notifications.register(actor.deviceId(), request.token(), request.routeHints()); }

    @PostMapping("/notifications/resolve") public GenericNotifier.Destination notification(@AuthenticationPrincipal Actor actor,
                                                                                          @Valid @RequestBody NotificationReference request) {
        rates.require("notification:" + actor.deviceId(), 20, 60);
        return notifications.resolve(actor, request.reference());
    }

    @DeleteMapping("/devices/push") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disablePush(@AuthenticationPrincipal Actor actor) { notifications.unregister(actor.deviceId()); }

    @GetMapping("/users/{handle}") public AccountDirectory.Contact contact(@PathVariable String handle) { return accounts.contact(handle); }
    @GetMapping("/users/id/{userId}") public AccountDirectory.Contact contact(@PathVariable UUID userId) { return accounts.contact(userId); }

    @GetMapping("/account/username") public AccountDirectory.Username username(@AuthenticationPrincipal Actor actor) {
        return accounts.username(actor.userId());
    }

    @PatchMapping("/account/username") public AccountDirectory.Username rename(@AuthenticationPrincipal Actor actor,
                                                                            @Valid @RequestBody AccountDirectory.UsernameChange change) {
        rates.require("rename:" + actor.userId(), 5, 60);
        return accounts.rename(actor.userId(), change);
    }

    @GetMapping("/account/profile") public AccountDirectory.Profile profile(@AuthenticationPrincipal Actor actor) {
        return accounts.profile(actor.userId());
    }

    @PatchMapping("/account/profile") public AccountDirectory.Profile updateProfile(@AuthenticationPrincipal Actor actor,
                                                                                  @Valid @RequestBody AccountDirectory.ProfileChange change) {
        rates.require("profile:" + actor.userId(), 10, 60);
        return accounts.updateProfile(actor.userId(), change);
    }

    @GetMapping("/users/id/{userId}/profile") public AccountDirectory.Profile profile(@PathVariable UUID userId) {
        return accounts.profile(userId);
    }

    @PostMapping("/keys") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void keys(@AuthenticationPrincipal Actor actor, @Valid @RequestBody AccountDirectory.KeyUpload request) {
        rates.require("keys:" + actor.deviceId(), 10, 60);
        accounts.uploadKeys(actor, request);
    }
    @GetMapping("/keys") public Map<String, Integer> keyCount(@AuthenticationPrincipal Actor actor) { return Map.of("remaining", accounts.keyCount(actor.deviceId())); }

    @PostMapping("/keys/{userId}/claim") public AccountDirectory.PreKey claim(@AuthenticationPrincipal Actor actor, @PathVariable UUID userId) {
        rates.require("claims:" + actor.deviceId(), 20, 60);
        return accounts.claim(userId);
    }

    private void recipient(UUID userId, UUID deviceId) {
        if (!accounts.active(userId, deviceId)) throw new ApiException(HttpStatus.NOT_FOUND, "not_found");
    }

    @PostMapping("/messages") @ResponseStatus(HttpStatus.CREATED)
    public Status send(@AuthenticationPrincipal Actor actor, @Valid @RequestBody SendRequest request) {
        rates.require("send:" + actor.deviceId(), 60, 60);
        recipient(request.recipientId(), request.recipientDeviceId());
        Status status = relay.send(actor, request);
        if (status.state() == State.QUEUED) notifications.wake(new GenericNotifier.Destination(request.recipientId(), request.recipientDeviceId(),
            actor.userId(), request.id(), status.expiresAt()));
        return status;
    }

    @GetMapping("/messages/pending") public List<Message> pending(@AuthenticationPrincipal Actor actor) { return relay.pending(actor); }
    @GetMapping("/messages/status") public List<Status> statuses(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) List<UUID> ids) { return relay.statuses(actor, ids); }

    @PostMapping("/messages/{id}/delivered") public Status delivered(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { return relay.acknowledge(actor, id, State.DELIVERED); }
    @PostMapping("/messages/{id}/read") public Status read(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { return relay.acknowledge(actor, id, State.READ); }
    @DeleteMapping("/messages/{id}") public Status delete(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { return relay.acknowledge(actor, id, State.DELETED); }

    @PutMapping(value = "/media/{id}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> upload(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @RequestParam UUID recipientId,
                                    @RequestParam UUID recipientDeviceId, @RequestParam long expiresAt, @RequestBody byte[] ciphertext) {
        rates.require("media:" + actor.deviceId(), 6, 60);
        recipient(recipientId, recipientDeviceId);
        relay.upload(actor, id, recipientDeviceId, expiresAt, ciphertext);
        return Map.of("id", id);
    }

    @GetMapping(value = "/media/{id}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] download(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { return relay.download(actor, id); }

    @DeleteMapping("/media/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMedia(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { relay.deleteMedia(actor, id); }
}