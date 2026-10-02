package com.fleetflow.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.fleetflow.order.dto.DailyOrderCount;
import com.fleetflow.order.repository.OrderRepository;

@DisplayName("OrderAnalyticsService daily series")
class OrderAnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Mock
    private OrderRepository orderRepository;

    @Test
    @DisplayName("a sparse day is filled with zeroes rather than skipped")
    void fillsGaps() {
        List<DailyOrderCount> observed = List.of(
                new DailyOrderCount("2026-09-30", 4L, 2L, 1L),
                new DailyOrderCount("2026-09-22", 1L, 0L, 0L));

        List<DailyOrderCount> series = OrderAnalyticsService.fillGaps(14, TODAY, observed);

        assertEquals(14, series.size());
        assertEquals("2026-09-17", series.get(0).date());
        assertEquals("2026-09-30", series.get(13).date());

        for (int index = 0; index < series.size(); index++) {
            assertEquals(TODAY.minusDays(13 - index), LocalDate.parse(series.get(index).date()),
                    "day " + index + " must be consecutive");
        }
    }

    @Test
    @DisplayName("only the observed days carry counts")
    void keepsObservedCountsAndZeroesTheRest() {
        List<DailyOrderCount> observed = List.of(
                new DailyOrderCount("2026-09-30", 4L, 2L, 1L),
                new DailyOrderCount("2026-09-22", 3L, 0L, 3L));

        List<DailyOrderCount> series = OrderAnalyticsService.fillGaps(14, TODAY, observed);

        // The series starts 13 days before the last day, so 2026-09-30 sits at 13 and
        // 2026-09-22 at 5, with a quiet day on either side.
        assertEquals(new DailyOrderCount("2026-09-30", 4L, 2L, 1L), series.get(13));
        assertEquals(new DailyOrderCount("2026-09-29", 0L, 0L, 0L), series.get(12));
        assertEquals(new DailyOrderCount("2026-09-22", 3L, 0L, 3L), series.get(5));
        assertEquals(new DailyOrderCount("2026-09-21", 0L, 0L, 0L), series.get(4));
    }

    @Test
    @DisplayName("an empty result set still produces a full series")
    void fillsEveryDayWhenThereIsNoActivity() {
        List<DailyOrderCount> series = OrderAnalyticsService.fillGaps(7, TODAY, List.of());

        assertEquals(7, series.size());
        assertTrue(series.stream().allMatch(day -> day.orders() == 0L && day.delivered() == 0L
                && day.cancelled() == 0L));
        assertEquals("2026-09-24", series.get(0).date());
        assertEquals("2026-09-30", series.get(6).date());
    }

    @Test
    @DisplayName("the window is clamped to a sane range")
    void clampsWindow() {
        assertEquals(1, OrderAnalyticsService.clampDays(0));
        assertEquals(1, OrderAnalyticsService.clampDays(-5));
        assertEquals(14, OrderAnalyticsService.clampDays(14));
        assertEquals(90, OrderAnalyticsService.clampDays(3650));
    }

    @Test
    @DisplayName("repository counts are keyed by day and closed over the requested window")
    void readsSparseRowsFromTheRepository() {
        MockitoAnnotations.openMocks(this);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(orderRepository.countDailyCreatedBetween(any(), any())).thenReturn(List.<Object[]>of(
                new Object[] { today.toString(), 4L, 2L, 1L },
                new Object[] { today.minusDays(5).toString(), 2L, 1L, 0L }));

        List<DailyOrderCount> series = new OrderAnalyticsService(orderRepository).dailyCounts(14);

        assertEquals(14, series.size());
        assertEquals(6L, series.stream().mapToLong(DailyOrderCount::orders).sum());
        assertEquals(12L, series.stream().filter(day -> day.orders() == 0L).count());
        assertEquals(new DailyOrderCount(today.toString(), 4L, 2L, 1L), series.get(series.size() - 1));
        assertEquals(new DailyOrderCount(today.minusDays(5).toString(), 2L, 1L, 0L), series.get(8));
    }
}
