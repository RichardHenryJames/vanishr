package app.vanishr.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;

import static app.vanishr.relay.RelayTypes.Actor;
import app.vanishr.relay.AccountDirectory.Contact;
import app.vanishr.relay.GroupMessages.ControlSend;

@RestController
@RequestMapping("/groups")
public class GroupController {
    public record Create(@NotNull UUID id) { }
    public record Revision(@Positive long revision) { }
    public record Invite(@Positive long revision, @NotNull @Size(min=1,max=199) List<@NotNull Contact> members) { }
    public record Controls(@Positive long revision, @NotNull @Size(min=1,max=32) List<@NotNull @Valid ControlSend> packets) { }
    public record Claims(@Positive long revision, @NotNull @Size(min=1,max=32) List<@NotNull UUID> users) { }
    public record Claimed(UUID userId, AccountDirectory.PreKey bundle) { }
    private final GroupDirectory groups;
    private final GroupMessages messages;
    private final AccountDirectory accounts;
    private final RateLimiter rates;
    private final GenericNotifier notifications;

    public GroupController(GroupDirectory groups, GroupMessages messages, AccountDirectory accounts, RateLimiter rates, GenericNotifier notifications) {
        this.groups=groups; this.messages=messages; this.accounts=accounts; this.rates=rates; this.notifications=notifications;
    }
    @GetMapping public List<GroupDirectory.Snapshot> list(@AuthenticationPrincipal Actor actor) { return groups.list(actor); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public GroupDirectory.Snapshot create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Create request) {
        rates.require("group-create:"+actor.userId(),5,60); return groups.create(actor,request.id());
    }
    @GetMapping("/{id}") public GroupDirectory.Snapshot get(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return groups.withGroup(actor,id,null,true,Function.identity());
    }
    @PostMapping("/{id}/invitations") public GroupDirectory.Snapshot invite(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Invite request) {
        rates.require("group-invite:"+actor.userId(),20,60);
        GroupDirectory.Snapshot group=groups.invite(actor,id,request.revision(),request.members());
        request.members().forEach(member -> notifications.wake(member.deviceId())); return group;
    }
    @PostMapping("/{id}/accept") public GroupDirectory.Snapshot accept(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        GroupDirectory.Snapshot group=groups.accept(actor,id,request.revision()); wake(group); return group;
    }
    @DeleteMapping("/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID userId, @RequestParam long revision) {
        groups.remove(actor,id,revision,userId);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @RequestParam long revision) { groups.close(actor,id,revision); }
    @PostMapping("/{id}/keys") public List<Claimed> keys(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Claims request) {
        rates.require("group-claims:"+actor.deviceId(),16,60);
        return groups.withGroup(actor,id,request.revision(),false,group -> {
            List<Claimed> result=new ArrayList<>();
            for (UUID user : new LinkedHashSet<>(request.users())) {
                GroupDirectory.Member member=group.member(user);
                if (!member.state().equals("ACTIVE") && !group.ownerId().equals(actor.userId())) throw new ApiException(HttpStatus.FORBIDDEN,"forbidden");
                AccountDirectory.Contact current=accounts.contact(user);
                if (!current.deviceId().equals(member.deviceId()) || !current.identityKey().equals(member.identityKey())) throw new ApiException(HttpStatus.CONFLICT,"group_identity_changed");
                try { result.add(new Claimed(user,accounts.claim(user))); }
                catch (ApiException failure) { if (!failure.getMessage().equals("prekeys_unavailable")) throw failure; }
            }
            return result;
        });
    }
    @PostMapping("/{id}/controls") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void controls(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Controls request) {
        rates.require("group-controls:"+actor.deviceId(),32,60);
        groups.withGroup(actor,id,request.revision(),false,group -> {
            for (GroupMessages.ControlSend packet : request.packets()) messages.control(actor,group,packet);
            request.packets().stream().map(GroupMessages.ControlSend::recipientDeviceId).distinct().forEach(notifications::wake);
            return null;
        });
    }
    @GetMapping("/{id}/controls") public List<GroupMessages.Control> controls(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return groups.withGroup(actor,id,null,true,group -> group.closed() ? List.of() : messages.controls(actor,id));
    }
    @DeleteMapping("/{id}/controls/{packet}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeControl(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID packet) {
        groups.withGroup(actor,id,null,true,group -> { messages.acknowledgeControl(actor,id,packet); return null; });
    }
    @PostMapping("/{id}/messages") @ResponseStatus(HttpStatus.CREATED)
    public GroupMessages.Status send(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody GroupMessages.Send request) {
        rates.require("group-send:"+id,60,60);
        if (request.media()!=null) rates.require("group-media:"+id,6,60);
        return groups.withGroup(actor,id,request.revision(),false,group -> {
            groups.requireCurrentDevices(group);
                GroupMessages.Status status=messages.send(actor,group,request);
                group.active().stream().filter(member -> !member.userId().equals(actor.userId())).forEach(member -> notifications.wake(
                    new GenericNotifier.Destination(member.userId(), member.deviceId(), id, request.id(), status.expiresAt())));
                return status;
        });
    }
    @GetMapping("/{id}/messages") public List<GroupMessages.Message> pending(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return groups.withGroup(actor,id,null,false,group -> group.closed() ? List.of() : messages.pending(actor,id));
    }
    @GetMapping("/{id}/status") public List<GroupMessages.Status> statuses(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return groups.withGroup(actor,id,null,false,group -> messages.statuses(actor,id));
    }
    @PostMapping("/{id}/messages/{message}/{action}") public GroupMessages.Status acknowledge(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
                                                                                        @PathVariable UUID message, @PathVariable String action) {
        String state=switch (action) { case "delivered" -> "DELIVERED"; case "read" -> "READ"; case "delete" -> "DELETED"; default -> throw new ApiException(HttpStatus.BAD_REQUEST,"invalid_request"); };
        return groups.withGroup(actor,id,null,false,group -> messages.acknowledge(actor,id,message,state));
    }
    @GetMapping(value="/{id}/messages/{message}/media",produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] media(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID message) {
        return groups.withGroup(actor,id,null,false,group -> {
            if (group.closed()) throw new ApiException(HttpStatus.NOT_FOUND,"not_found");
            return messages.media(actor,id,message);
        });
    }
    private void wake(GroupDirectory.Snapshot group) { group.active().forEach(member -> notifications.wake(member.deviceId())); }
}