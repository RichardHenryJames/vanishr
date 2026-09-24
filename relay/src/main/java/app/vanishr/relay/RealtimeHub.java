package app.vanishr.relay;

import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@EnableWebSocket
public class RealtimeHub extends TextWebSocketHandler implements WebSocketConfigurer {
    private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, WebSocketSession> photoSessions = new ConcurrentHashMap<>();
    private final AuthService auth;
    public RealtimeHub(AuthService auth) { this.auth = auth; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/events", "/photo-events").addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet)) return false;
                Object key = servlet.getServletRequest().getAttribute("sessionKey");
                if (key == null) return false;
                attributes.put("sessionKey", key);
                attributes.put("photoChannel", servlet.getServletRequest().getRequestURI().equals("/photo-events"));
                return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception failure) { }
        });
    }

    private RelayTypes.Actor actor(WebSocketSession session) { return (RelayTypes.Actor) ((Authentication) session.getPrincipal()).getPrincipal(); }
    private Map<UUID, WebSocketSession> channel(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("photoChannel")) ? photoSessions : sessions;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(64);
        session.setBinaryMessageSizeLimit(64);
        WebSocketSession previous = channel(session).put(actor(session).deviceId(), session);
        if (previous != null) previous.close(CloseStatus.NORMAL);
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { channel(session).remove(actor(session).deviceId(), session); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception { session.close(CloseStatus.POLICY_VIOLATION); }

    public String connection(RelayTypes.Actor actor) {
        return connection(actor, sessions);
    }

    public String photoConnection(RelayTypes.Actor actor) { return connection(actor, photoSessions); }

    private String connection(RelayTypes.Actor actor, Map<UUID, WebSocketSession> channel) {
        WebSocketSession session = channel.get(actor.deviceId());
        return session != null && session.isOpen() && actor.equals(actor(session))
                && auth.activeSession((String) session.getAttributes().get("sessionKey"), actor) ? session.getId() : null;
    }

    public void wake(UUID deviceId) {
        wake(deviceId, sessions);
    }

    public void wakePhotos(UUID deviceId) { wake(deviceId, photoSessions); }

    private void wake(UUID deviceId, Map<UUID, WebSocketSession> channel) {
        WebSocketSession session = channel.get(deviceId);
        if (session == null) return;
        try {
            if (!auth.activeSession((String) session.getAttributes().get("sessionKey"), actor(session))) { session.close(CloseStatus.POLICY_VIOLATION); return; }
            synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage("{\"event\":\"new_message\"}")); }
        } catch (Exception failure) { channel.remove(deviceId, session); }
    }

    @Scheduled(fixedDelay = 30_000)
    public void expireConnections() {
        for (WebSocketSession session : java.util.stream.Stream.concat(sessions.values().stream(), photoSessions.values().stream()).toList()) {
            try {
                if (!auth.activeSession((String) session.getAttributes().get("sessionKey"), actor(session))) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception failure) {
                try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
            }
        }
    }
}