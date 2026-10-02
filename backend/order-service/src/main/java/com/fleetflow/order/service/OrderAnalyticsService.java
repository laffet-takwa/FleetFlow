package com.fleetflow.order.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;

import com.fleetflow.order.dto.DailyOrderCount;
import com.fleetflow.order.repository.OrderRepository;

@Service
public class OrderAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(OrderAnalyticsService.class);

    private static final int MAX_DAYS = 90;

    private final OrderRepository orderRepository;

    public OrderAnalyticsService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Order volume per calendar day, oldest first.
     *
     * <p>The window always ends today and always spans exactly {@code days} entries,
     * because a chart that silently skips quiet days reads as missing data.
     */
    @Transactional(readOnly = true)
    public List<DailyOrderCount> dailyCounts(int days) {
        int window = clampDays(days);
        LocalDate lastDay = LocalDate.now(ZoneOffset.UTC);
        LocalDate firstDay = lastDay.minusDays(window - 1L);

        List<DailyOrderCount> observed = new ArrayList<>();
        for (Object[] row : orderRepository.countDailyCreatedBetween(
                firstDay.atStartOfDay(ZoneOffset.UTC).toInstant(),
                lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {

            observed.add(new DailyOrderCount(
                    (String) row[0], asLong(row[1]), asLong(row[2]), asLong(row[3])));
        }

        List<DailyOrderCount> series = fillGaps(window, lastDay, observed);
        log.debug("Built a {} day order series ending {} [correlationId={}]", window, lastDay,
                CorrelationId.getOrCreate());
        return series;
    }

    /**
     * Expands sparse per-day rows into a dense series. Pure, so the gap behaviour is
     * assertable without a database and no caller can forget to close a hole.
     */
    public static List<DailyOrderCount> fillGaps(int days, LocalDate lastDay, Collection<DailyOrderCount> observed) {
        Map<LocalDate, DailyOrderCount> byDate = new HashMap<>();
        for (DailyOrderCount entry : observed) {
            byDate.put(LocalDate.parse(entry.date()), entry);
        }

        List<DailyOrderCount> series = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = lastDay.minusDays(offset);
            series.add(byDate.getOrDefault(date, new DailyOrderCount(date.toString(), 0L, 0L, 0L)));
        }
        return series;
    }

    static int clampDays(int days) {
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
