package app.vanishr.android;

import app.vanishr.crypto.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static app.vanishr.android.RelayApi.JSON;

final class GroupChat {
    record Member(UUID userId, UUID deviceId, String identityKey, String handle, String displayName, String state, long invitedUntil) {
        String name() { return displayName==null ? handle : displayName; }
        GroupRoster.Member identity() { return new GroupRoster.Member(userId,deviceId,identityKey); }
    }
    record Snapshot(UUID id, UUID ownerId, long revision, UUID epoch, boolean closed, List<Member> members) {
        Member member(UUID userId) { return members.stream().filter(member -> member.userId().equals(userId)).findFirst().orElseThrow(() -> new SecurityException("Group member is missing")); }
        GroupRoster roster() { return new GroupRoster(id,ownerId,epoch,revision,members.stream().filter(member -> member.state().equals("ACTIVE")).map(Member::identity).toList()); }
        List<Member> active() { return members.stream().filter(member -> member.state().equals("ACTIVE")).toList(); }
    }
    record Conversation(Snapshot snapshot, String name, String issue) { UUID id() { return snapshot.id(); } }
    record Approval(GroupRoster roster, String name, long expiresAt) { }
    record Distribution(byte[] value, long expiresAt) { }
    record Invitation(String name,long expiresAt) { }
    record ControlBody(int version, String kind, UUID groupId, UUID epoch, long revision, UUID senderId, UUID recipientId,
                       String name, String rosterDigest, byte[] distribution, long expiresAt) { }
    record ControlSend(UUID id, UUID recipientId, UUID recipientDeviceId, UUID epoch, long revision, long expiresAt, int type, byte[] ciphertext) { }
    record Control(UUID id, UUID groupId, UUID senderId, UUID senderDeviceId, UUID recipientId, UUID recipientDeviceId,
                   UUID epoch, long revision, long expiresAt, int type, byte[] ciphertext) { }
    record Controls(long revision, List<ControlSend> packets) { }
    record Claims(long revision, List<UUID> users) { }
    record Claimed(UUID userId, PublicBundle bundle) { }
    record Invite(long revision, List<ChatEngine.Contact> members) { }
    record Send(UUID id, UUID epoch, long revision, ChatEnvelope.Expiry expiry, long expiresAt, byte[] ciphertext, UUID mediaId, byte[] media) { }
    record Outbox(UUID groupId, Send message) { }
    record Message(UUID id, UUID groupId, UUID epoch, long revision, UUID senderId, UUID senderDeviceId,
                   ChatEnvelope.Expiry expiry, long expiresAt, byte[] ciphertext, UUID mediaId) { }
    record Status(UUID id, long expiresAt, int recipients, int delivered, int read, String state) { }
    record Ack(UUID groupId, UUID id, long expiresAt, String action) { }
    record QueuedControl(UUID groupId, String marker, ControlSend packet) { }
    private record Planned(Member recipient, String kind, String marker) { }
    private final ChatEngine engine;
    private final AndroidVault vault;
    private boolean supported=true;

    GroupChat(ChatEngine engine, AndroidVault vault) { this.engine=engine; this.vault=vault; }
    private RelayApi api() { return engine.groupApi(); }
    private SignalClient signal() { return engine.groupSignal(); }
    private UUID user() { return engine.account().userId(); }
    private <Value> Value read(String key, Class<Value> type) {
        byte[] value=vault.get(key);
        return value==null ? null : JSON.fromJson(new String(value,StandardCharsets.UTF_8),type);
    }
    private void write(String key, Object value) { vault.put(key,JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    private String path(UUID id) { return "/groups/"+id; }
    private String epochKey(UUID id,UUID epoch) { return id+"/"+epoch; }
    private String validName(String value) {
        if (value==null || value.strip().isEmpty() || value.strip().length()>64 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid group name");
        return value.strip();
    }

    List<Conversation> conversations() {
        return vault.names("group/").stream().map(name -> read(name,Conversation.class)).sorted(Comparator.comparing(Conversation::name)).toList();
    }
    Conversation get(UUID id) { return id==null ? null : read("group/"+id,Conversation.class); }
    boolean owner(Conversation group) { return group.snapshot().ownerId().equals(user()); }
    boolean invited(Conversation group) { return group.snapshot().member(user()).state().equals("INVITED"); }
    boolean ready(Conversation group) {
        if (group==null || group.snapshot().closed() || invited(group)) return false;
        if (!owner(group) && !ownerVerified(group)) return false;
        Approval approval=approval(group.id(),group.snapshot().epoch());
        return approval!=null && approval.roster().digest().equals(group.snapshot().roster().digest()) && group.issue()==null;
    }
    String status(Conversation group) {
        if (group.snapshot().closed()) return "Group closed";
        if (invited(group)) return "Invitation";
        if (group.issue()!=null) return group.issue();
        return ready(group) ? group.snapshot().active().size()+" members" : "Waiting for owner";
    }
    String senderName(UUID id,UUID sender) {
        Conversation group=get(id);
        if (group==null) return "Member";
        return engine.peers().stream().filter(peer -> peer.userId().equals(sender)).map(ChatEngine.Peer::name).findFirst()
                .orElseGet(() -> group.snapshot().members().stream().filter(member -> member.userId().equals(sender)).map(Member::name).findFirst().orElse("Member"));
    }
    private Approval approval(UUID group,UUID epoch) { return read("group-approval/"+epochKey(group,epoch),Approval.class); }
    private void snapshot(Snapshot value) throws Exception {
        if (value==null || value.members()==null || value.members().size()>200 || value.members().stream().anyMatch(Objects::isNull)) throw new SecurityException("Invalid group membership");
        if (value.members().stream().anyMatch(member -> !Set.of("ACTIVE","INVITED").contains(member.state()) || member.handle()==null || !member.handle().matches("[a-z0-9_]{3,32}")))
            throw new SecurityException("Invalid group member metadata");
        new GroupRoster(value.id(),value.ownerId(),value.epoch(),value.revision(),value.members().stream().map(Member::identity).toList());
        value.roster();
        Member own=value.member(user());
        if (!own.deviceId().equals(engine.account().deviceId()) || !own.identityKey().equals(Base64.getEncoder().encodeToString(signal().publicIdentity()))) throw new SecurityException("Group device identity changed");
        Conversation previous=get(value.id());
        if (previous!=null && (previous.snapshot().revision()>value.revision() || !previous.snapshot().ownerId().equals(value.ownerId())
                || previous.snapshot().revision()==value.revision() && !previous.snapshot().epoch().equals(value.epoch()))) throw new SecurityException("Group membership rollback rejected");
        String name=previous==null ? "Private group" : previous.name();
        Conversation next=new Conversation(value,name,null);
        if (!next.equals(previous)) vault.transaction(() -> {
            if (previous!=null && !previous.snapshot().epoch().equals(value.epoch())) {
                Approval old=approval(value.id(),previous.snapshot().epoch());
                if (old!=null) write("group-approval/"+epochKey(value.id(),previous.snapshot().epoch()),new Approval(old.roster(),old.name(),System.currentTimeMillis()+86_400_000L));
            }
            write("group/"+value.id(),next); return null;
        });
    }
    private void issue(UUID id,String message) throws Exception {
        Conversation group=get(id);
        if (group!=null && !Objects.equals(group.issue(),message)) vault.transaction(() -> { write("group/"+id,new Conversation(group.snapshot(),group.name(),message)); return null; });
    }

    UUID create(String name) throws Exception {
        name=validName(name);
        UUID id=UUID.randomUUID(); String title=name;
        vault.transaction(() -> { write("group-title/"+id,title); write("group-allowed/"+id,List.of(new GroupRoster.Member(user(),engine.account().deviceId(),Base64.getEncoder().encodeToString(signal().publicIdentity())))); return null; });
        Snapshot created=api().call("POST","/groups",Map.of("id",id),Snapshot.class);
        if (created==null || !id.equals(created.id()) || !user().equals(created.ownerId())) throw new SecurityException("Group creation identity changed");
        snapshot(created); approveOwner(created); return id;
    }

    void invite(UUID id,List<ChatEngine.Peer> peers) throws Exception {
        Conversation group=Objects.requireNonNull(get(id));
        if (!owner(group) || peers.isEmpty() || peers.size()+group.snapshot().members().size()>200) throw new IllegalArgumentException("Group member limit reached");
        List<GroupRoster.Member> allowed=new ArrayList<>(Arrays.asList(read("group-allowed/"+id,GroupRoster.Member[].class)));
        List<ChatEngine.Contact> contacts=new ArrayList<>();
        for (ChatEngine.Peer peer : peers) {
            if (!engine.peers().contains(peer) || !signal().isVerified(peer.userId())) throw new SecurityException("Verify the invited contact first");
            contacts.add(new ChatEngine.Contact(peer.userId(),peer.deviceId(),peer.identityKey()));
            allowed.removeIf(member -> member.userId().equals(peer.userId())); allowed.add(new GroupRoster.Member(peer.userId(),peer.deviceId(),peer.identityKey()));
        }
        vault.transaction(() -> { write("group-allowed/"+id,allowed); return null; });
        Snapshot updated=api().call("POST",path(id)+"/invitations",new Invite(group.snapshot().revision(),contacts),Snapshot.class);
        snapshot(updated); publish(updated,32);
    }

    private ChatEngine.Peer verifiedOwner(Snapshot group) {
        Member owner=group.member(group.ownerId());
        return engine.peers().stream().filter(peer -> peer.userId().equals(owner.userId()) && peer.deviceId().equals(owner.deviceId()) && peer.identityKey().equals(owner.identityKey()))
                .findFirst().orElseThrow(() -> new SecurityException("Verify the group owner first"));
    }
    boolean ownerVerified(Conversation group) { try { verifiedOwner(group.snapshot()); return true; } catch (SecurityException failure) { return false; } }
    void accept(UUID id) throws Exception {
        Conversation group=Objects.requireNonNull(get(id)); verifiedOwner(group.snapshot());
        if (!invitationReady(group)) throw new SecurityException("Wait for the encrypted invitation");
        snapshot(api().call("POST",path(id)+"/accept",Map.of("revision",group.snapshot().revision()),Snapshot.class));
    }
    boolean invitationReady(Conversation group) {
        Invitation invitation=read("group-invitation/"+group.id(),Invitation.class);
        return ownerVerified(group) && invitation!=null && invitation.expiresAt()>System.currentTimeMillis()
                && group.snapshot().member(user()).invitedUntil()>System.currentTimeMillis();
    }
    void remove(UUID id,UUID member) throws Exception {
        Conversation group=Objects.requireNonNull(get(id));
        if (owner(group)) {
            GroupRoster.Member[] allowed=read("group-allowed/"+id,GroupRoster.Member[].class);
            vault.transaction(() -> { write("group-allowed/"+id,Arrays.stream(allowed).filter(value -> !value.userId().equals(member)).toList()); return null; });
        }
        api().call("DELETE",path(id)+"/members/"+member+"?revision="+group.snapshot().revision(),null,Void.class);
        if (member.equals(user())) forget(id); else snapshot(api().call("GET",path(id),null,Snapshot.class));
    }
    void close(UUID id) throws Exception {
        Conversation group=Objects.requireNonNull(get(id));
        api().call("DELETE",path(id)+"?revision="+group.snapshot().revision(),null,Void.class); forget(id);
    }
    void forget(UUID id) throws Exception {
        for (ChatEngine.Entry entry : engine.entries(id)) engine.erase(entry,null);
        vault.transaction(() -> {
            vault.remove("group/"+id); vault.remove("group-title/"+id); vault.remove("group-allowed/"+id); vault.remove("group-invitation/"+id);
            for (String prefix : List.of("group-approval/","group-signal/","group-key/","group-mark/","group-packet/","group-distribution/")) for (String name : vault.names(prefix+id+"/")) vault.remove(name);
            for (String name : vault.names("group-out/")) if (read(name,Outbox.class).groupId().equals(id)) vault.remove(name);
            for (String name : vault.names("group-control-out/")) if (read(name,QueuedControl.class).groupId().equals(id)) vault.remove(name);
            return null;
        });
    }

    private void approveOwner(Snapshot group) throws Exception {
        if (!group.ownerId().equals(user())) return;
        GroupRoster.Member[] allowed=read("group-allowed/"+group.id(),GroupRoster.Member[].class);
        if (allowed==null) throw new SecurityException("Owner approval is unavailable");
        List<GroupRoster.Member> verified=Arrays.asList(allowed);
        GroupRoster roster=group.roster();
        for (GroupRoster.Member member : roster.members()) if (!verified.contains(member)) throw new SecurityException("Unapproved member in group");
        for (Member member : group.members()) {
            if (member.userId().equals(user())) continue;
            if (!verified.contains(member.identity()) || engine.peers().stream().noneMatch(peer -> peer.userId().equals(member.userId()) && peer.deviceId().equals(member.deviceId()) && peer.identityKey().equals(member.identityKey())))
                throw new SecurityException("Group member verification changed");
        }
        String title=validName(read("group-title/"+group.id(),String.class));
        approve(group,roster,title);
    }

    private void approve(Snapshot snapshot,GroupRoster roster,String title) throws Exception {
        if (!roster.digest().equals(snapshot.roster().digest())) throw new SecurityException("Owner-approved membership does not match");
        Approval previous=approval(roster.groupId(),roster.epoch());
        if (previous!=null && !previous.roster().digest().equals(roster.digest())) throw new SecurityException("Group epoch was reused");
        vault.transaction(() -> {
            write("group-approval/"+epochKey(roster.groupId(),roster.epoch()),new Approval(roster,title,System.currentTimeMillis()+86_400_000L));
            write("group/"+roster.groupId(),new Conversation(snapshot,title,null));
            return null;
        });
    }

    private SignalGroup cipher(UUID group,UUID epoch) { return new SignalGroup(vault,group,epoch,user()); }
    private byte[] distribution(Snapshot group) throws Exception {
        String key="group-distribution/"+epochKey(group.id(),group.epoch());
        Distribution saved=read(key,Distribution.class);
        if (saved!=null && saved.expiresAt()>System.currentTimeMillis()) return saved.value();
        byte[] value=cipher(group.id(),group.epoch()).distribution();
        vault.transaction(() -> { write(key,new Distribution(value,System.currentTimeMillis()+86_400_000L)); return null; });
        return value;
    }
    private boolean marked(String marker) {
        byte[] value=vault.get(marker);
        return value!=null && Long.parseLong(new String(value,StandardCharsets.US_ASCII))>System.currentTimeMillis();
    }

    private boolean publish(Snapshot snapshot,int budget) throws Exception {
        if (snapshot.closed()) return false;
        boolean owner=snapshot.ownerId().equals(user());
        Approval approval=approval(snapshot.id(),snapshot.epoch());
        if (approval==null || !approval.roster().digest().equals(snapshot.roster().digest())) return false;
        List<Planned> planned=new ArrayList<>();
        String base="group-mark/"+epochKey(snapshot.id(),snapshot.epoch())+"/";
        for (Member member : snapshot.members()) {
            if (member.userId().equals(user())) continue;
            String kind=member.state().equals("INVITED") ? "INVITE" : "ROSTER";
            if (owner && !marked(base+kind+"/"+member.userId())) planned.add(new Planned(member,kind,base+kind+"/"+member.userId()));
            if (member.state().equals("ACTIVE") && !marked(base+"KEY/"+member.userId())) planned.add(new Planned(member,"KEY",base+"KEY/"+member.userId()));
        }
        if (planned.isEmpty()) {
            String seed="group-distribution/"+epochKey(snapshot.id(),snapshot.epoch());
            if (vault.get(seed)!=null) vault.transaction(() -> { vault.remove(seed); return null; });
            return true;
        }
        List<Planned> batch=planned.subList(0,Math.min(budget,planned.size()));
        List<UUID> missing=batch.stream().map(Planned::recipient).map(Member::userId).distinct().filter(member -> !signal().hasSession(member)).toList();
        if (!missing.isEmpty()) {
            Claimed[] claimed=api().call("POST",path(snapshot.id())+"/keys",new Claims(snapshot.revision(),missing),Claimed[].class);
            if (claimed!=null) for (Claimed item : claimed) {
                Member member=snapshot.member(item.userId());
                if (!missing.contains(item.userId()) || item.bundle()==null || !member.identityKey().equals(Base64.getEncoder().encodeToString(item.bundle().identityKey()))) throw new SecurityException("Group key identity changed");
                signal().verifyPeer(member.userId(),Base64.getDecoder().decode(member.identityKey()));
                signal().establish(member.userId(),item.bundle(),Instant.now());
            }
        }
        byte[] distribution=distribution(snapshot);
        try {
            for (Planned item : batch) {
                if (!signal().hasSession(item.recipient().userId())) continue;
                signal().verifyPeer(item.recipient().userId(),Base64.getDecoder().decode(item.recipient().identityKey()));
                if (vault.get(item.marker().replace("group-mark/","group-packet/"))!=null) continue;
                vault.transaction(() -> {
                    long deadline=System.currentTimeMillis()+86_400_000L;
                    ControlBody body=new ControlBody(1,item.kind(),snapshot.id(),snapshot.epoch(),snapshot.revision(),user(),item.recipient().userId(),
                        owner ? approval.name() : null,approval.roster().digest(),item.kind().equals("KEY") ? distribution : null,deadline);
                    byte[] plaintext=JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                    SignalClient.Packet encrypted;
                    try { encrypted=signal().encrypt(item.recipient().userId(),plaintext,Instant.now()); }
                    finally { Arrays.fill(plaintext,(byte)0); }
                    UUID id=UUID.randomUUID();
                    ControlSend packet=new ControlSend(id,item.recipient().userId(),item.recipient().deviceId(),snapshot.epoch(),snapshot.revision(),deadline,encrypted.type(),encrypted.ciphertext());
                    write("group-control-out/"+id,new QueuedControl(snapshot.id(),item.marker(),packet));
                    write(item.marker().replace("group-mark/","group-packet/"),id);
                    return null;
                });
            }
        } finally { Arrays.fill(distribution,(byte)0); }
        flushControls(snapshot);
        boolean complete=planned.stream().allMatch(item -> marked(item.marker()));
        if (complete) vault.transaction(() -> { vault.remove("group-distribution/"+epochKey(snapshot.id(),snapshot.epoch())); return null; });
        return complete;
    }

    private void flushControls(Snapshot snapshot) throws Exception {
        List<QueuedControl> pending=vault.names("group-control-out/").stream().map(name -> read(name,QueuedControl.class))
                .filter(item -> item.groupId().equals(snapshot.id()) && item.packet().epoch().equals(snapshot.epoch()) && item.packet().expiresAt()>System.currentTimeMillis()).limit(32).toList();
        if (pending.isEmpty()) return;
        api().call("POST",path(snapshot.id())+"/controls",new Controls(snapshot.revision(),pending.stream().map(QueuedControl::packet).toList()),Void.class);
        vault.transaction(() -> {
            for (QueuedControl item : pending) {
                vault.put(item.marker(),Long.toString(item.packet().expiresAt()-60_000).getBytes(StandardCharsets.US_ASCII));
                vault.remove("group-control-out/"+item.packet().id()); vault.remove(item.marker().replace("group-mark/","group-packet/"));
            }
            return null;
        });
    }

    private void receiveControl(Snapshot snapshot,Control packet) throws Exception {
        if (packet.expiresAt()<=System.currentTimeMillis()) return;
        if (packet.expiresAt()>System.currentTimeMillis()+86_400_000L) throw new SecurityException("Invalid group control deadline");
        if (marked("group-control-seen/"+packet.id())) { acknowledgeControl(packet); return; }
        if (!snapshot.id().equals(packet.groupId()) || !user().equals(packet.recipientId()) || !engine.account().deviceId().equals(packet.recipientDeviceId())) throw new SecurityException("Invalid group control recipient");
        if (packet.revision()<snapshot.revision() && approval(snapshot.id(),packet.epoch())==null) { acknowledgeControl(packet); return; }
        GroupRoster.Member sender;
        boolean owner=packet.senderId().equals(snapshot.ownerId());
        if (owner) {
            ChatEngine.Peer verified=verifiedOwner(snapshot);
            sender=new GroupRoster.Member(verified.userId(),verified.deviceId(),verified.identityKey());
        } else {
            Approval accepted=approval(snapshot.id(),packet.epoch());
            if (accepted==null) return;
            sender=accepted.roster().member(packet.senderId());
        }
        if (!sender.deviceId().equals(packet.senderDeviceId())) throw new SecurityException("Group sender device changed");
        signal().verifyPeer(sender.userId(),Base64.getDecoder().decode(sender.identityKey()));
        vault.transaction(() -> {
            byte[] plaintext=signal().decrypt(sender.userId(),new SignalClient.Packet(packet.type(),packet.ciphertext()));
            try {
                ControlBody body=JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),ControlBody.class);
                if (body==null || body.version()!=1 || !packet.groupId().equals(body.groupId()) || !packet.epoch().equals(body.epoch()) || packet.revision()!=body.revision()
                    || !sender.userId().equals(body.senderId()) || !user().equals(body.recipientId()) || body.expiresAt()!=packet.expiresAt()) throw new SecurityException("Invalid group control context");
                boolean current=snapshot.epoch().equals(body.epoch()) && snapshot.revision()==body.revision();
                if (owner && current && (body.kind().equals("INVITE") || body.kind().equals("ROSTER") || body.kind().equals("KEY"))) {
                    String title=validName(body.name());
                    if (!snapshot.roster().digest().equals(body.rosterDigest())) throw new SecurityException("Owner-approved membership changed");
                    if (snapshot.member(user()).state().equals("INVITED")) {
                        write("group-invitation/"+snapshot.id(),new Invitation(title,body.expiresAt())); write("group/"+snapshot.id(),new Conversation(snapshot,title,null));
                    } else approve(snapshot,snapshot.roster(),title);
                }
                if (body.kind().equals("KEY")) {
                    Approval approved=approval(snapshot.id(),body.epoch());
                    if (approved==null || !approved.roster().digest().equals(body.rosterDigest())) throw new SecurityException("Group keys need owner approval");
                    approved.roster().member(sender.userId());
                    String key="group-key/"+epochKey(snapshot.id(),body.epoch())+"/"+sender.userId();
                    if (!marked(key)) {
                        cipher(snapshot.id(),body.epoch()).accept(sender.userId(),body.distribution());
                        vault.put(key,Long.toString(body.expiresAt()).getBytes(StandardCharsets.US_ASCII));
                    }
                } else if (!owner || !Set.of("INVITE","ROSTER").contains(body.kind())) throw new SecurityException("Invalid group control kind");
                vault.put("group-control-seen/"+packet.id(),Long.toString(packet.expiresAt()).getBytes(StandardCharsets.US_ASCII));
                write("group-control-ack/"+packet.id(),new Ack(snapshot.id(),packet.id(),packet.expiresAt(),"control"));
            } finally { Arrays.fill(plaintext,(byte)0); }
            return null;
        });
        acknowledgeControl(packet);
    }
    private void acknowledgeControl(Control packet) throws Exception {
        try { api().call("DELETE",path(packet.groupId())+"/controls/"+packet.id(),null,Void.class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status!=404 && failure.status!=410) throw failure; }
        vault.transaction(() -> { vault.remove("group-control-ack/"+packet.id()); return null; });
    }

    void sync() throws Exception {
        if (!supported) return;
        Snapshot[] snapshots;
        try { snapshots=api().call("GET","/groups",null,Snapshot[].class); }
        catch (RelayApi.ApiFailure failure) { if (failure.status==404) { supported=false; return; } throw failure; }
        if (snapshots==null || snapshots.length>40) throw new SecurityException("Invalid group inventory");
        Set<UUID> present=new HashSet<>();
        for (Snapshot snapshot : snapshots) {
            present.add(snapshot.id());
            try {
                if (snapshot.closed()) { if (get(snapshot.id())!=null) forget(snapshot.id()); continue; }
                snapshot(snapshot);
                if (snapshot.ownerId().equals(user())) approveOwner(snapshot);
                else verifiedOwner(snapshot);
                Control[] controls=api().call("GET",path(snapshot.id())+"/controls",null,Control[].class);
                if (controls==null || controls.length>64) throw new SecurityException("Invalid group controls");
                Arrays.sort(controls,Comparator.comparing(packet -> !packet.senderId().equals(snapshot.ownerId())));
                for (Control packet : controls) receiveControl(snapshot,packet);
                if (snapshot.member(user()).state().equals("INVITED")) continue;
                publish(snapshot,32);
                flushOutgoing(snapshot);
                flushAcks(snapshot.id());
                Message[] incoming=api().call("GET",path(snapshot.id())+"/messages",null,Message[].class);
                if (incoming==null || incoming.length>32) throw new SecurityException("Invalid group inbox");
                for (Message message : incoming) receive(snapshot,message);
                flushAcks(snapshot.id());
                Status[] statuses=api().call("GET",path(snapshot.id())+"/status",null,Status[].class);
                if (statuses!=null && statuses.length>256) throw new SecurityException("Invalid group status list");
                if (statuses!=null) vault.transaction(() -> {
                    for (Status status : statuses) {
                        if (status.id()==null || status.recipients()<1 || status.recipients()>199 || status.read()<0 || status.delivered()<status.read()
                                || status.delivered()>status.recipients() || status.state()==null || !Set.of("QUEUED","DELIVERED","READ","DELETED").contains(status.state()))
                            throw new SecurityException("Invalid group receipt");
                        ChatEngine.Entry entry=read("entry/"+status.id(),ChatEngine.Entry.class);
                        if (entry!=null && entry.outgoing() && entry.groupEpoch()!=null && entry.peerId().equals(snapshot.id()) && entry.expiresAt()==status.expiresAt())
                            write("entry/"+entry.id(),entry.withState(status.state().equals("DELETED") ? "DELETED" : status.delivered()+" delivered / "+status.read()+" of "+status.recipients()+" read"));
                    }
                    return null;
                });
            } catch (SecurityException failure) { issue(snapshot.id(),"Identity verification required"); }
            catch (RelayApi.ApiFailure failure) {
                if (failure.status==401) throw failure;
                issue(snapshot.id(),failure.status==409 ? "Membership changed; refreshing" : "Connection pending");
            }
        }
        for (Conversation group : conversations()) if (!present.contains(group.id())) forget(group.id());
    }

    void send(UUID groupId,String text,byte[] image,ChatEnvelope.Expiry expiry,UUID id,long createdAt,Runnable stored) throws Exception {
        engine.purge();
        Snapshot current=api().call("GET",path(groupId),null,Snapshot.class); snapshot(current);
        if (current.ownerId().equals(user())) approveOwner(current);
        if (!ready(get(groupId))) throw new SecurityException("Wait for verified group membership");
        if (current.active().size()<2 || vault.names("entry/").size()>=100) throw new IllegalStateException("Group or local message capacity unavailable");
        long deadline=Math.addExact(createdAt,expiry.milliseconds);
        UUID mediaId=image==null ? null : UUID.randomUUID();
        ImageCipher.EncryptedImage encrypted=image==null ? null : ImageCipher.encrypt(mediaId,image);
        ChatEnvelope content=new ChatEnvelope(1,id,user(),engine.account().deviceId(),groupId,current.epoch(),createdAt,deadline,expiry,text,
                encrypted==null ? null : new ChatEnvelope.Attachment(mediaId,encrypted.key(),encrypted.nonce(),"image/jpeg"));
        GroupEnvelope envelope=new GroupEnvelope(1,groupId,current.epoch(),current.revision(),content);
        ChatEngine.Entry entry=new ChatEngine.Entry(id,groupId,deadline,expiry,true,image!=null,"PENDING",user(),current.epoch(),current.revision(),user());
        try {
            envelope.verify(groupId,current.epoch(),current.revision(),id,user(),engine.account().deviceId(),expiry,deadline,mediaId,Instant.now());
            vault.transaction(() -> {
                SignalGroup cipher=cipher(groupId,current.epoch());
                byte[] distribution=distribution(current); Arrays.fill(distribution,(byte)0);
                byte[] plaintext=JSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
                byte[] ciphertext;
                try { ciphertext=cipher.encrypt(plaintext); } finally { Arrays.fill(plaintext,(byte)0); }
                write("group-out/"+id,new Outbox(groupId,new Send(id,current.epoch(),current.revision(),expiry,deadline,ciphertext,mediaId,encrypted==null ? null : encrypted.ciphertext())));
                engine.storeContent(entry,new ChatEngine.Content(content,image)); write("entry/"+id,entry);
                return null;
            });
        } catch (Exception failure) { AndroidVault.deleteContentKey(deadline,id,user()); throw failure; }
        finally { if (encrypted!=null) Arrays.fill(encrypted.key(),(byte)0); }
        stored.run();
        try { publish(current,32); flushOutgoing(current); }
        catch (IOException failure) { engine.online=false; if (failure instanceof RelayApi.ApiFailure apiFailure && apiFailure.status==401) engine.invalidateToken(); }
    }

    private void flushOutgoing(Snapshot snapshot) throws Exception {
        List<Outbox> pending=vault.names("group-out/").stream().map(name -> read(name,Outbox.class)).filter(item -> item.groupId().equals(snapshot.id())).toList();
        if (pending.isEmpty()) return;
        if (!publish(snapshot,32)) return;
        for (Outbox outbox : pending) {
            Send message=outbox.message(); ChatEngine.Entry entry=read("entry/"+message.id(),ChatEngine.Entry.class);
            if (entry==null) { vault.transaction(() -> { vault.remove("group-out/"+message.id()); return null; }); continue; }
            if (message.revision()!=snapshot.revision() || !message.epoch().equals(snapshot.epoch())) {
                vault.transaction(() -> { write("entry/"+entry.id(),entry.withState("Not sent: membership changed")); vault.remove("group-out/"+message.id()); return null; }); continue;
            }
            api().call("POST",path(snapshot.id())+"/messages",message,Status.class);
            vault.transaction(() -> { write("entry/"+entry.id(),entry.withState("QUEUED")); vault.remove("group-out/"+entry.id()); return null; });
            if (entry.expiry()==ChatEnvelope.Expiry.VIEW_ONCE) {
                AndroidVault.deleteContentKey(entry.expiresAt(),entry.id(),entry.keyOwner());
                vault.transaction(() -> { vault.remove("body/"+entry.id()); return null; });
            }
        }
    }

    private void receive(Snapshot current,Message message) throws Exception {
        if (message.expiresAt()<=System.currentTimeMillis() || marked("seen/"+message.id()) || vault.names("entry/").size()>=100) return;
        if (!current.id().equals(message.groupId())) throw new SecurityException("Group routing changed");
        Approval approval=approval(message.groupId(),message.epoch());
        if (approval==null || approval.roster().revision()!=message.revision()) return;
        GroupRoster.Member sender=approval.roster().member(message.senderId());
        if (!sender.deviceId().equals(message.senderDeviceId()) || !approval.roster().member(user()).deviceId().equals(engine.account().deviceId())) throw new SecurityException("Group device changed");
        if (vault.get("group-key/"+epochKey(message.groupId(),message.epoch())+"/"+sender.userId())==null) return;
        byte[] imageCiphertext=message.mediaId()==null ? null : api().groupMedia(message.groupId(),message.id());
        try {
            vault.transaction(() -> {
                byte[] plaintext=cipher(message.groupId(),message.epoch()).decrypt(sender.userId(),message.ciphertext());
                try {
                    GroupEnvelope envelope=JSON.fromJson(new String(plaintext,StandardCharsets.UTF_8),GroupEnvelope.class);
                    envelope.verify(message.groupId(),message.epoch(),message.revision(),message.id(),sender.userId(),sender.deviceId(),message.expiry(),message.expiresAt(),message.mediaId(),Instant.now());
                    byte[] image=imageCiphertext==null ? null : ImageCipher.decrypt(message.mediaId(),imageCiphertext,envelope.content().image().key(),envelope.content().image().nonce());
                    ChatEngine.Entry entry=new ChatEngine.Entry(message.id(),message.groupId(),message.expiresAt(),message.expiry(),false,image!=null,"DELIVERED",user(),message.epoch(),message.revision(),sender.userId());
                    try { engine.storeContent(entry,new ChatEngine.Content(envelope.content(),image)); } finally { if (image!=null) Arrays.fill(image,(byte)0); }
                    write("entry/"+entry.id(),entry); acknowledge(entry,"delivered");
                    vault.put("seen/"+entry.id(),Long.toString(entry.expiresAt()).getBytes(StandardCharsets.US_ASCII));
                } finally { Arrays.fill(plaintext,(byte)0); }
                return null;
            });
        } catch (Exception failure) { AndroidVault.deleteContentKey(message.expiresAt(),message.id(),user()); throw failure; }
    }
    void acknowledge(ChatEngine.Entry entry,String action) { write("group-ack/"+entry.id(),new Ack(entry.peerId(),entry.id(),entry.expiresAt(),action)); }
    private void flushAcks(UUID group) throws Exception {
        for (String name : vault.names("group-ack/")) {
            Ack ack=read(name,Ack.class); if (!ack.groupId().equals(group)) continue;
            try { api().call("POST",path(group)+"/messages/"+ack.id()+"/"+ack.action(),null,Void.class); }
            catch (RelayApi.ApiFailure failure) { if (failure.status!=404 && failure.status!=410) throw failure; }
            vault.transaction(() -> { vault.remove(name); return null; });
        }
        for (String name : vault.names("group-control-ack/")) {
            Ack ack=read(name,Ack.class); if (!ack.groupId().equals(group)) continue;
            try { api().call("DELETE",path(group)+"/controls/"+ack.id(),null,Void.class); }
            catch (RelayApi.ApiFailure failure) { if (failure.status!=404 && failure.status!=410) throw failure; }
            vault.transaction(() -> { vault.remove(name); return null; });
        }
    }

    void purge(String prefix,long now) {
        for (String name : vault.names(prefix+"group-invitation/")) if (read(name,Invitation.class).expiresAt()<=now) vault.remove(name);
        for (String name : vault.names(prefix+"group-distribution/")) if (read(name,Distribution.class).expiresAt()<=now) vault.remove(name);
        for (String name : vault.names(prefix+"group-out/")) if (read(name,Outbox.class).message().expiresAt()<=now) vault.remove(name);
        for (String part : List.of("group-ack/","group-control-ack/")) for (String name : vault.names(prefix+part)) if (read(name,Ack.class).expiresAt()<=now) vault.remove(name);
        for (String name : vault.names(prefix+"group-control-out/")) {
            QueuedControl item=read(name,QueuedControl.class);
            if (item.packet().expiresAt()<=now) { vault.remove(name); vault.remove(prefix+item.marker().replace("group-mark/","group-packet/")); }
        }
        for (String part : List.of("group-mark/","group-control-seen/")) for (String name : vault.names(prefix+part)) if (Long.parseLong(new String(vault.get(name),StandardCharsets.US_ASCII))<=now) vault.remove(name);
        for (String name : vault.names(prefix+"group-approval/")) {
            Approval approval=read(name,Approval.class);
            Conversation group=read(prefix+"group/"+approval.roster().groupId(),Conversation.class);
            if (approval.expiresAt()>now || group!=null && !group.snapshot().closed() && group.snapshot().epoch().equals(approval.roster().epoch())) continue;
            String suffix=epochKey(approval.roster().groupId(),approval.roster().epoch())+"/";
            for (String part : List.of("group-signal/","group-key/","group-mark/","group-packet/")) for (String record : vault.names(prefix+part+suffix)) vault.remove(record);
            vault.remove(name);
        }
    }
}