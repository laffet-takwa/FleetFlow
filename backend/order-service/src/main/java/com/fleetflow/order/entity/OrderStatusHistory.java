package com.fleetflow.order.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One entry of the order timeline, appended on every status change.
 *
 * <p>Unlike {@link Order} this row owns its timestamp explicitly: it records when the
 * transition happened, not when the row happened to be written, and it is never
 * updated afterwards.
 */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private OrderStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private StatusSource source;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public static OrderStatusHistory of(OrderStatus status, OrderStatus previousStatus, StatusSource source,
            String note, Instant changedAt) {

        OrderStatusHistory history = new OrderStatusHistory();
        history.setStatus(status);
        history.setPreviousStatus(previousStatus);
        history.setSource(source);
        history.setNote(note);
        history.setChangedAt(changedAt);
        return history;
    }

    public void attachTo(Order order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public OrderStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(OrderStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public StatusSource getSource() {
        return source;
    }

    public void setSource(StatusSource source) {
        this.source = source;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }
}
