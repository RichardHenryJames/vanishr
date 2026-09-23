package app.vanishr.relay;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static app.vanishr.relay.RelayTypes.Actor;

@RestController
@RequestMapping("/profile/packets")
public class ProfileController {
    private final ProfileMessages messages;
    private final RateLimiter rates;

    public ProfileController(ProfileMessages messages, RateLimiter rates) { this.messages=messages; this.rates=rates; }

    @PostMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void send(@AuthenticationPrincipal Actor actor, @Valid @RequestBody ProfileMessages.Send request) {
        rates.require("profile-packets:"+actor.deviceId(),30,60);
        messages.send(actor,request);
    }

    @GetMapping public List<ProfileMessages.Packet> pending(@AuthenticationPrincipal Actor actor) { return messages.pending(actor); }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) { messages.acknowledge(actor,id); }
}