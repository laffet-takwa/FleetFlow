package com.fleetflow.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;

import com.fleetflow.notification.dto.NotificationResponse;
import com.fleetflow.notification.entity.Notification;
import com.fleetflow.notification.entity.NotificationLevel;
import com.fleetflow.notification.entity.NotificationType;
import com.fleetflow.notification.mapper.NotificationMapper;
import com.fleetflow.notification.repository.NotificationRepository;
import com.fleetflow.notification.sse.NotificationSseRegistry;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-02T13:41:07.512Z");

    @Mock
    private NotificationRepository repository;

    @Mock
    private NotificationSseRegistry sseRegistry;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, new NotificationMapper(), sseRegistry);
        authenticate(8L);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPersistsTheRowAndPushesItToTheRecipientsStream() {
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            saved.setId(412L);
            saved.setCreatedAt(CREATED_AT);
            return saved;
        });

        NotificationResponse response = service.create(8L, new NotificationDraft(
                NotificationType.ORDER_CONFIRMED, NotificationLevel.INFO,
                "Order #42 confirmed", "Your order #42 has been received and is being prepared.", 42L, null));

        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(stored.capture());
        assertThat(stored.getValue().getUserId()).isEqualTo(8L);
        assertThat(stored.getValue().getType()).isEqualTo(NotificationType.ORDER_CONFIRMED);
        assertThat(stored.getValue().getLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(stored.getValue().getTitle()).isEqualTo("Order #42 confirmed");
        assertThat(stored.getValue().getMessage())
                .isEqualTo("Your order #42 has been received and is being prepared.");
        assertThat(stored.getValue().getOrderId()).isEqualTo(42L);
        assertThat(stored.getValue().getDeliveryId()).isNull();
        assertThat(stored.getValue().isRead()).isFalse();

        ArgumentCaptor<NotificationResponse> pushed = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(sseRegistry).push(eq(8L), pushed.capture());
        assertThat(pushed.getValue()).isEqualTo(response);
        assertThat(pushed.getValue().id()).isEqualTo(412L);
        assertThat(pushed.getValue().type()).isEqualTo("ORDER_CONFIRMED");
        assertThat(pushed.getValue().level()).isEqualTo("INFO");
        assertThat(pushed.getValue().createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void createNeverOverwritesAnEarlierNotificationOfTheSameUser() {
        AtomicLong sequence = new AtomicLong(400L);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
            Notification saved = invocation.getArgument(0);
            saved.setId(sequence.incrementAndGet());
            saved.setCreatedAt(CREATED_AT);
            return saved;
        });

        service.create(8L, new NotificationDraft(NotificationType.ORDER_CONFIRMED, NotificationLevel.INFO,
                "Order #1 confirmed", "Your order #1 has been received and is being prepared.", 1L, null));
        service.create(8L, new NotificationDraft(NotificationType.DELIVERY_STARTED, NotificationLevel.INFO,
                "On the way", "Your delivery for order #1 is on the way.", 1L, 7L));

        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).saveAndFlush(stored.capture());
        assertThat(stored.getAllValues()).extracting(Notification::getUserId).containsOnly(8L);
        assertThat(stored.getAllValues()).extracting(Notification::getTitle)
                .containsExactly("Order #1 confirmed", "On the way");
        assertThat(stored.getAllValues()).extracting(Notification::getId).doesNotHaveDuplicates();

        ArgumentCaptor<NotificationResponse> pushed = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(sseRegistry, times(2)).push(eq(8L), pushed.capture());
        assertThat(pushed.getAllValues()).extracting(NotificationResponse::title)
                .containsExactly("Order #1 confirmed", "On the way");
    }

    @Test
    void aUserCannotMarkAnotherUsersNotificationAsRead() {
        when(repository.findById(999L)).thenReturn(Optional.of(notification(999L, 9L, false)));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.markAsRead(8L, 999L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");

        verify(repository, never()).save(any(Notification.class));
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void markingAnUnknownNotificationAsReadIsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.markAsRead(8L, 404L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.NOT_FOUND, "expected NOT_FOUND");

        verifyNoInteractions(sseRegistry);
    }

    @Test
    void markAsReadFlipsTheRowAndPushesTheRemainingUnreadCount() {
        when(repository.findById(412L)).thenReturn(Optional.of(notification(412L, 8L, false)));
        when(repository.countByUserIdAndReadFalse(8L)).thenReturn(2L);

        service.markAsRead(8L, 412L);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isRead()).isTrue();
        verify(sseRegistry).pushUnreadCount(8L, 2L);
    }

    @Test
    void readingAnAlreadyReadNotificationWritesNothing() {
        when(repository.findById(412L)).thenReturn(Optional.of(notification(412L, 8L, true)));

        service.markAsRead(8L, 412L);

        verify(repository, never()).save(any(Notification.class));
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void markAllAsReadOnlyTouchesTheRowsOfThatUser() {
        List<Notification> unread = List.of(notification(412L, 8L, false), notification(413L, 8L, false));
        when(repository.findByUserIdAndReadFalse(8L)).thenReturn(unread);

        service.markAllAsRead(8L);

        ArgumentCaptor<Long> queried = ArgumentCaptor.forClass(Long.class);
        verify(repository).findByUserIdAndReadFalse(queried.capture());
        assertThat(queried.getValue()).isEqualTo(8L);
        verify(repository, never()).findByUserIdAndReadFalse(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Notification::getId).containsExactly(412L, 413L);
        assertThat(saved.getValue()).allMatch(Notification::isRead);
        assertThat(saved.getValue()).extracting(Notification::getUserId).containsOnly(8L);
        verify(sseRegistry).pushReadAll(8L);
    }

    @Test
    void markAllAsReadOnAnEmptyCentrePushesNothing() {
        when(repository.findByUserIdAndReadFalse(8L)).thenReturn(List.of());

        service.markAllAsRead(8L);

        verify(repository, never()).saveAll(any());
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void unreadCountIsScopedToTheCaller() {
        when(repository.countByUserIdAndReadFalse(8L)).thenReturn(3L);

        assertThat(service.unreadCount(8L).unreadCount()).isEqualTo(3L);
    }

    @Test
    void listIsNewestFirstAndScopedToTheCaller() {
        when(repository.findByUserId(eq(8L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification(413L, 8L, false), notification(412L, 8L, true))));

        var page = service.list(8L, false, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserId(eq(8L), pageable.capture());
        Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
        assertThat(order).isNotNull();
        assertThat(order.isAscending()).isFalse();
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(NotificationResponse::id).containsExactly(413L, 412L);
    }

    @Test
    void unreadOnlyListUsesTheUnreadQuery() {
        when(repository.findByUserIdAndReadFalse(eq(8L), any(Pageable.class))).thenReturn(Page.empty());

        service.list(8L, true, 0, 20);

        verify(repository, never()).findByUserId(anyLong(), any(Pageable.class));
    }

    @Test
    void listRejectsAnUnusablePageRequest() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.list(8L, false, -1, 20))
                .matches(ex -> ex.getErrorCode() == ErrorCode.BAD_REQUEST, "expected BAD_REQUEST");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.list(8L, false, 0, 0))
                .matches(ex -> ex.getErrorCode() == ErrorCode.BAD_REQUEST, "expected BAD_REQUEST");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.list(8L, false, 0, 500))
                .matches(ex -> ex.getErrorCode() == ErrorCode.BAD_REQUEST, "expected BAD_REQUEST");
    }

    @Test
    void aUserCannotDeleteAnotherUsersNotification() {
        when(repository.findById(999L)).thenReturn(Optional.of(notification(999L, 9L, false)));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.delete(8L, 999L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");

        verify(repository, never()).delete(any(Notification.class));
    }

    @Test
    void deleteRemovesTheNotificationOfTheCaller() {
        Notification own = notification(412L, 8L, true);
        when(repository.findById(412L)).thenReturn(Optional.of(own));

        service.delete(8L, 412L);

        verify(repository).delete(own);
    }

    @Test
    void everyUserScopedCallRefusesAUserIdThatIsNotTheAuthenticatedOne() {
        // The controller takes the user id from the JWT; a caller that passes anybody
        // else's must be rejected before a single row is read.
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.list(9L, false, 0, 20))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.unreadCount(9L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.markAllAsRead(9L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.delete(9L, 412L))
                .matches(ex -> ex.getErrorCode() == ErrorCode.FORBIDDEN, "expected FORBIDDEN");

        verifyNoInteractions(repository, sseRegistry);
    }

    @Test
    void theOperationsDeskReadsItsOwnCentre() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);
        when(repository.countByUserIdAndReadFalse(2L)).thenReturn(4L);

        assertThat(service.unreadCount(2L).unreadCount()).isEqualTo(4L);
    }

    private void authenticate(Long userId) {
        authenticate(userId, "customer1@fleetflow.local", FleetRole.CUSTOMER);
    }

    private void authenticate(Long userId, String email, FleetRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtPrincipal(userId, email, role),
                null,
                List.of(new SimpleGrantedAuthority(role.authority()))));
    }

    private static Notification notification(Long id, Long userId, boolean read) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setType(NotificationType.ORDER_CONFIRMED);
        notification.setLevel(NotificationLevel.INFO);
        notification.setTitle("Order #42 confirmed");
        notification.setMessage("Your order #42 has been received and is being prepared.");
        notification.setOrderId(42L);
        notification.setRead(read);
        notification.setCreatedAt(CREATED_AT);
        return notification;
    }
}
