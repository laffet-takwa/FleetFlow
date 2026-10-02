package com.fleetflow.order.mapper;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fleetflow.order.dto.OrderItemLineResponse;
import com.fleetflow.order.dto.OrderItemResponse;
import com.fleetflow.order.dto.OrderResponse;
import com.fleetflow.order.dto.OrderSummaryResponse;
import com.fleetflow.order.dto.OrderTimelineEntry;
import com.fleetflow.order.entity.Order;
import com.fleetflow.order.entity.OrderItem;
import com.fleetflow.order.entity.OrderStatusHistory;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order, List<OrderItem> items, List<OrderStatusHistory> timeline,
            int itemCount) {

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getSubtotal(),
                order.getDeliveryFee(),
                order.getTotalAmount(),
                order.getCurrency(),
                itemCount,
                order.getDeliveryAddress(),
                order.getCity(),
                order.getPostalCode(),
                order.getDeliveryId(),
                order.getCancelledReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                items.stream().map(this::toItemResponse).toList(),
                timeline.stream().map(this::toTimelineEntry).toList());
    }

    public OrderSummaryResponse toSummary(Order order, int itemCount) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getCustomerId(),
                itemCount,
                order.getTotalAmount(),
                order.getStatus().name());
    }

    public List<OrderItemLineResponse> toItemLines(List<OrderItem> items) {
        return items.stream()
                .map(item -> new OrderItemLineResponse(item.getProductId(), item.getQuantity()))
                .toList();
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getProductId(),
                item.getProductName(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineSubtotal());
    }

    private OrderTimelineEntry toTimelineEntry(OrderStatusHistory history) {
        return new OrderTimelineEntry(
                history.getStatus().name(),
                history.getPreviousStatus() == null ? null : history.getPreviousStatus().name(),
                history.getSource().name(),
                history.getNote(),
                history.getChangedAt());
    }
}
