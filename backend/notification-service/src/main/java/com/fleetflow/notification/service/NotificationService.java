package com.fleetflow.notification.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.SecurityUtils;

import com.fleetflow.notification.dto.NotificationResponse;
import com.fleetflow.notification.dto.UnreadCountResponse;
import com.fleetflow.notification.entity.Notification;
import com.fleetflow.notification.mapper.NotificationMapper;
import com.fleetflow.notification.repository.NotificationRepository;
import com.fleetflow.notification.sse.NotificationSseRegistry;

/**
 * Owns the notification lifecycle.
 *
 * <p>Every read and mutation is scoped to a user id that the controller takes from the
 * JWT subject, so a notification is only ever visible to the person it was written
 * for. Staff do not get an exception: they are notified about the events that concern
 * their work in the same way as anybody else.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository repository;
    private final NotificationMapper mapper;
    private final NotificationSseRegistry sseRegistry;

    public NotificationService(NotificationRepository repository, NotificationMapper mapper,
            NotificationSseRegistry sseRegistry) {
        this.repository = repository;
        this.mapper = mapper;
        this.sseRegistry = sseRegistry;
    }

    /**
     * Stores one notification and pushes it to every stream the recipient has open.
     *
     * <p>Each call inserts its own row, never reusing a previous one, because read
     * state belongs to a notification and not to a user.
     *
     * <p>The push happens before the transaction commits: a rare case is a browser
     * rendering a notification whose insert is then rolled back. Paying that to avoid
     * a transaction listener per event is a deliberate trade - the reader always sees
     * the row on the next fetch, and the window is microseconds.
     *
     * @return the stored notification, as it was pushed
     */
    @Transactional
    public NotificationResponse create(long userId, NotificationDraft draft) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(draft.type());
        notification.setLevel(draft.level());
        notification.setTitle(draft.title());
        notification.setMessage(draft.message());
        notification.setOrderId(draft.orderId());
        notification.setDeliveryId(draft.deliveryId());

        // saveAndFlush, not save: createdAt is assigned on flush and the pushed payload
        // must carry the same timestamp the row now holds.
        Notification saved = repository.saveAndFlush(notification);
        NotificationResponse response = mapper.toResponse(saved);
        sseRegistry.push(userId, response);
        log.info("Notification {} ({}) stored for userId={} [correlationId={}]",
                saved.getId(), draft.type(), userId, CorrelationId.getOrCreate());
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(long userId, boolean unreadOnly, int page, int size) {
        requireSelf(userId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "page must be zero or greater and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // The id tiebreaker keeps paging stable when several rows share a timestamp.
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<Notification> result = unreadOnly
                ? repository.findByUserIdAndReadFalse(userId, pageable)
                : repository.findByUserId(userId, pageable);
        List<NotificationResponse> content = result.getContent().stream().map(mapper::toResponse).toList();
        return PageResponse.from(result, content);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(long userId) {
        requireSelf(userId);
        return new UnreadCountResponse(repository.countByUserIdAndReadFalse(userId));
    }

    @Transactional
    public void markAsRead(long userId, long id) {
        requireSelf(userId);
        Notification notification = ownedBy(userId, id);
        if (notification.isRead()) {
            return;
        }
        notification.setRead(true);
        repository.save(notification);
        sseRegistry.pushUnreadCount(userId, repository.countByUserIdAndReadFalse(userId));
    }

    /** Flips every unread row of the user and tells the open streams what the badge holds. */
    @Transactional
    public void markAllAsRead(long userId) {
        requireSelf(userId);
        List<Notification> unread = repository.findByUserIdAndReadFalse(userId);
        if (unread.isEmpty()) {
            return;
        }
        unread.forEach(notification -> notification.setRead(true));
        repository.saveAll(unread);
        // The count is queried rather than assumed to be zero: a notification a consumer
        // committed while this transaction was open is still unread, and no later event
        // would correct a badge that claims otherwise, because create() pushes no count.
        sseRegistry.pushReadAll(userId, repository.countByUserIdAndReadFalse(userId));
        log.info("Marked {} notifications as read for userId={} [correlationId={}]",
                unread.size(), userId, CorrelationId.getOrCreate());
    }

    @Transactional
    public void delete(long userId, long id) {
        requireSelf(userId);
        repository.delete(ownedBy(userId, id));
    }

    public SseEmitter subscribe(long userId) {
        return sseRegistry.subscribe(requireSelf(userId));
    }

    /**
     * The user id an endpoint hands over has to be the one the access token carries.
     * Checking it here rather than trusting the caller means a mistake in a controller
     * can never widen the scope of a query, and it is the reason the service methods
     * take no user id on the wire.
     */
    private long requireSelf(long userId) {
        if (SecurityUtils.requireUserId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You may only access your own notifications");
        }
        return userId;
    }

    /**
     * Loads a notification and refuses it when it belongs to somebody else.
     *
     * <p>Distinct from {@code SecurityUtils.requireSelfOrStaff}: a notification is
     * written for one person and reads as that person's account activity, so there is
     * no staff override - an unknown id and somebody else's id are kept apart on
     * purpose, the first is a 404 and the second is a 403.
     */
    private Notification ownedBy(long userId, long id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Notification", id));
        if (notification.getUserId() == null || notification.getUserId() != userId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You may only access your own notifications");
        }
        return notification;
    }
}
