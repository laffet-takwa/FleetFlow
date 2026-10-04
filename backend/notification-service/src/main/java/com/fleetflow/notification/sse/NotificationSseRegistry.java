package com.fleetflow.notification.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.notification.dto.NotificationResponse;

/**
 * Keeps one {@link SseEmitter} per open browser tab and fans notifications out to all
 * of them, so a user signed in on a phone and a laptop sees the same updates.
 *
 * <p>Emitters live in a plain map behind a lock rather than in a concurrent map: the
 * registry is mutated rarely (a subscribe or a disconnect) and every mutation touches
 * two structures, which a lock keeps consistent.
 */
@Component
public class NotificationSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSseRegistry.class);

    /** Browsers drop an idle connection, so the stream is recycled roughly twice an hour. */
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    public static final String EVENT_CONNECTED = "connected";
    public static final String EVENT_NOTIFICATION = "notification";
    public static final String EVENT_UNREAD_COUNT = "unread-count";
    public static final String EVENT_READ_ALL = "read-all";

    private final Map<Long, Set<SseEmitter>> emittersByUser = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper objectMapper;

    public NotificationSseRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Registers a new stream for {@code userId} and confirms it with a {@code connected}
     * event, which is what lets the client distinguish "subscribed" from "hanging".
     */
    public SseEmitter subscribe(long userId) {
        SseEmitter emitter = newEmitter();
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> {
            remove(userId, emitter);
            emitter.complete();
        });
        emitter.onError(throwable -> remove(userId, emitter));

        lock.lock();
        try {
            emittersByUser.computeIfAbsent(userId, key -> new LinkedHashSet<>()).add(emitter);
        } finally {
            lock.unlock();
        }

        send(userId, emitter, EVENT_CONNECTED, "{\"userId\":" + userId + "}");
        log.debug("SSE stream opened for userId={} [correlationId={}]", userId, CorrelationId.getOrCreate());
        return emitter;
    }

    public void push(long userId, NotificationResponse notification) {
        broadcast(userId, EVENT_NOTIFICATION, notification);
    }

    public void pushUnreadCount(long userId, long count) {
        broadcast(userId, EVENT_UNREAD_COUNT, new UnreadCountPayload(count));
    }

    /**
     * Tells every stream of the user that its badge has been cleared, carrying the count
     * the server actually holds rather than a fixed zero.
     */
    public void pushReadAll(long userId, long remainingUnread) {
        broadcast(userId, EVENT_READ_ALL, new UnreadCountPayload(remainingUnread));
    }

    /**
     * Overridable so a test can drive the emitter lifecycle that a servlet container
     * normally drives; production always uses the recycling stream above.
     */
    SseEmitter newEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MILLIS);
    }

    private void broadcast(long userId, String event, Object payload) {
        String json = serialise(payload);
        for (SseEmitter emitter : currentEmitters(userId)) {
            send(userId, emitter, event, json);
        }
    }

    private void send(long userId, SseEmitter emitter, String event, String json) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // The tab was closed mid-send; dropping the emitter is the only way out,
            // otherwise every later notification would retry a dead connection. Completing
            // it as well releases the async request now: an emitter that is only removed
            // from this map has no path left that ends the request, so the container would
            // hold its thread until the stream timeout fires.
            remove(userId, emitter);
            emitter.complete();
            log.debug("Dropped SSE stream of userId={}: {}", userId, ex.getMessage());
        }
    }

    private Set<SseEmitter> currentEmitters(long userId) {
        lock.lock();
        try {
            Set<SseEmitter> current = emittersByUser.get(userId);
            return current == null ? Set.of() : Set.copyOf(current);
        } finally {
            lock.unlock();
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        lock.lock();
        try {
            Set<SseEmitter> current = emittersByUser.get(userId);
            if (current == null) {
                return;
            }
            current.remove(emitter);
            if (current.isEmpty()) {
                emittersByUser.remove(userId);
            }
        } finally {
            lock.unlock();
        }
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            // A payload that cannot be rendered is a bug in this service, not in the
            // caller's request, so it is reported rather than turned into a 4xx.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Cannot serialise SSE payload", ex);
        }
    }

    private record UnreadCountPayload(long unreadCount) {
    }
}
