package app.vanishr.relay;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static app.vanishr.relay.RelayTypes.Actor;

@RestController
@RequestMapping("/remote-photos")
public class RemotePhotosController {
    private final RemotePhotos photos;
    private final RateLimiter rates;
    public RemotePhotosController(RemotePhotos photos, RateLimiter rates) { this.photos = photos; this.rates = rates; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public RemotePhotos.Session request(@AuthenticationPrincipal Actor actor, @Valid @RequestBody RemotePhotos.Request request) {
        rates.require("photo-request:" + actor.deviceId(), 3, 60);
        return photos.request(actor, request);
    }
    @GetMapping public List<RemotePhotos.Session> pending(@AuthenticationPrincipal Actor actor) { return photos.pending(actor); }
    @GetMapping("/{id}") public RemotePhotos.Session get(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { return photos.get(actor, id); }
    @PostMapping("/{id}/exchange") public RemotePhotos.Delivery exchange(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
                                                                        @Valid @RequestBody RemotePhotos.Exchange request) {
        rates.require("photo-exchange:" + actor.deviceId(), 600, 60);
        return photos.exchange(actor, id, request);
    }
    @PostMapping("/{id}/accept") public RemotePhotos.Session accept(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
                                                                   @Valid @RequestBody RemotePhotos.Approval approval) {
        return photos.accept(actor, id, approval);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { photos.stop(actor, id); }
}