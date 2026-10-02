package app.vanishr.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static app.vanishr.relay.RelayTypes.Actor;

@RestController
public class SafetyController {
    public record Deletion(@NotNull @Pattern(regexp = "DELETE") String confirmation,
                           @Pattern(regexp = DeletionReceipts.PROOF_PATTERN) String deletionProof) {
        @Override public String toString() { return "AccountDeletion[redacted]"; }
    }
    private final AccountSafety safety;
    private final BlockDirectory blocks;
    private final SafetyReports reports;
    private final DeletionReceipts receipts;
    private final RateLimiter rates;

    public SafetyController(AccountSafety safety, BlockDirectory blocks, SafetyReports reports, DeletionReceipts receipts, RateLimiter rates) {
        this.safety = safety; this.blocks = blocks; this.reports = reports;
        this.receipts = receipts; this.rates = rates;
    }

    @DeleteMapping("/account") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Deletion request) { safety.delete(actor, request.deletionProof()); }

    @PostMapping("/account/deletion/status")
    public DeletionReceipts.Status deletionStatus(@Valid @RequestBody DeletionReceipts.Proof request) {
        rates.require("deletion-status:" + DeletionReceipts.digest(request.deletionProof()), 30, 60);
        return receipts.status(request.deletionProof());
    }

    @PostMapping("/account/deletion/retry")
    public DeletionReceipts.Status retryDeletion(@Valid @RequestBody DeletionReceipts.Proof request) {
        rates.require("deletion-retry:" + DeletionReceipts.digest(request.deletionProof()), 5, 60);
        return safety.retry(request.deletionProof());
    }

    @GetMapping("/account/blocks")
    public List<UUID> blocks(@AuthenticationPrincipal Actor actor) { return blocks.list(actor.userId()); }

    @PutMapping("/account/blocks/{peerId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal Actor actor, @PathVariable UUID peerId) { safety.block(actor, peerId); }

    @DeleteMapping("/account/blocks/{peerId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Actor actor, @PathVariable UUID peerId) { blocks.remove(actor.userId(), peerId); }

    @PostMapping("/safety/reports") @ResponseStatus(HttpStatus.CREATED)
    public SafetyReports.Accepted report(@AuthenticationPrincipal Actor actor, @Valid @RequestBody SafetyReports.Submission request) {
        return reports.submit(actor, request);
    }

    @GetMapping("/safety/reports")
    public List<SafetyReports.Report> reports(@AuthenticationPrincipal Actor actor) { return reports.list(actor); }

    @DeleteMapping("/safety/reports/{reportId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void review(@AuthenticationPrincipal Actor actor, @PathVariable UUID reportId) { reports.review(actor, reportId); }
}
