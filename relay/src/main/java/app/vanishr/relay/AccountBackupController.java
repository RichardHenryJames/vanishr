package app.vanishr.relay;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static app.vanishr.relay.RelayTypes.Actor;

@RestController
@RequestMapping("/account/backup")
public class AccountBackupController {
    private final AccountBackups backups;
    private final RateLimiter rates;

    public AccountBackupController(AccountBackups backups, RateLimiter rates) { this.backups = backups; this.rates = rates; }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public AccountBackups.Status replace(@AuthenticationPrincipal Actor actor, @RequestBody byte[] ciphertext) {
        rates.require("backup-write:" + actor.deviceId(), 6, 60);
        return backups.put(actor.userId(), ciphertext);
    }

    @GetMapping(produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] download(@AuthenticationPrincipal Actor actor) {
        rates.require("backup-read:" + actor.deviceId(), 12, 60);
        return backups.get(actor.userId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
    }

    @GetMapping("/status")
    public AccountBackups.Status status(@AuthenticationPrincipal Actor actor) {
        rates.require("backup-read:" + actor.deviceId(), 12, 60);
        return backups.status(actor.userId());
    }

    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Actor actor) {
        rates.require("backup-write:" + actor.deviceId(), 6, 60);
        backups.delete(actor.userId());
    }
}
