package app.vanishr.relay;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import static app.vanishr.relay.RelayTypes.Actor;

@RestController
@RequestMapping("/presence")
public class PresenceController {
    private final Presence presence;
    private final RateLimiter rates;

    public PresenceController(Presence presence, RateLimiter rates) { this.presence = presence; this.rates = rates; }

    @PostMapping public List<Presence.Status> update(@AuthenticationPrincipal Actor actor,
            @RequestAttribute("sessionKey") String sessionKey, @Valid @RequestBody Presence.Update update) {
        rates.require("presence:" + actor.deviceId(), 45, 60);
        return presence.update(actor, sessionKey, update);
    }
}