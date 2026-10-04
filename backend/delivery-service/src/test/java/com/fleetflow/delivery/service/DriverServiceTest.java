package com.fleetflow.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.delivery.dto.DriverResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.mapper.DriverMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("DriverService")
class DriverServiceTest {

    /** Local key, deliberately unlike the platform identity below. */
    private static final long DRIVER_ID = 3L;
    private static final long DRIVER_USER_ID = 33L;

    @Mock
    private DriverRepository driverRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private VehicleRepository vehicleRepository;

    private DriverService driverService() {
        return new DriverService(driverRepository, deliveryRepository, vehicleRepository, new DriverMapper());
    }

    @Test
    @DisplayName("GET /api/drivers/me resolves the driver by the JWT subject, not the local key")
    void resolvesTheDriverByUserId() {
        when(driverRepository.findByUserId(DRIVER_USER_ID)).thenReturn(Optional.of(driver()));
        when(deliveryRepository.findOpenByDriverIds(List.of(DRIVER_ID), DeliveryStatus.OPEN)).thenReturn(List.of());
        when(deliveryRepository.countByDriverIdsAndStatus(List.of(DRIVER_ID), DeliveryStatus.DELIVERED))
                .thenReturn(List.of());

        DriverResponse me = driverService().getByUserId(DRIVER_USER_ID);

        assertThat(me.id()).isEqualTo(DRIVER_ID);
        assertThat(me.userId()).isEqualTo(DRIVER_USER_ID);
        assertThat(me.fullName()).isEqualTo("Karim Ben Salah");
        // The lookup must go through userId; the local key would have found a different row.
        verify(driverRepository).findByUserId(DRIVER_USER_ID);
    }

    @Test
    @DisplayName("a JWT subject with no driver record is a 404, not a wrong driver")
    void unknownUserIdIsNotFound() {
        when(driverRepository.findByUserId(DRIVER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverService().getByUserId(DRIVER_USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a driver may not declare ON_DELIVERY for themselves")
    void onDeliveryIsReservedForOperations() {
        when(driverRepository.findLockedByUserId(DRIVER_USER_ID)).thenReturn(Optional.of(driver()));

        assertThatThrownBy(() -> driverService().setOwnStatus(DRIVER_USER_ID, "ON_DELIVERY"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("a driver still on an open delivery cannot offer themselves as available")
    void refusesAvailabilityWhileADeliveryIsOpen() {
        Driver driver = driver();
        driver.setStatus(DriverStatus.ON_DELIVERY);
        when(driverRepository.findLockedByUserId(DRIVER_USER_ID)).thenReturn(Optional.of(driver));
        when(deliveryRepository.existsByDriverIdAndStatusIn(DRIVER_ID, DeliveryStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> driverService().setOwnStatus(DRIVER_USER_ID, "AVAILABLE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("still has a delivery in progress");

        assertThat(driver.getStatus()).isEqualTo(DriverStatus.ON_DELIVERY);
    }

    @Test
    @DisplayName("declaring a status reads the driver under a write lock, so assign cannot interleave")
    void declaresStatusUnderAWriteLock() {
        Driver driver = driver();
        when(driverRepository.findLockedByUserId(DRIVER_USER_ID)).thenReturn(Optional.of(driver));
        when(deliveryRepository.existsByDriverIdAndStatusIn(DRIVER_ID, DeliveryStatus.OPEN)).thenReturn(false);
        when(deliveryRepository.findOpenByDriverIds(List.of(DRIVER_ID), DeliveryStatus.OPEN)).thenReturn(List.of());
        when(deliveryRepository.countByDriverIdsAndStatus(List.of(DRIVER_ID), DeliveryStatus.DELIVERED))
                .thenReturn(List.of());

        driverService().setOwnStatus(DRIVER_USER_ID, "OFFLINE");

        // assign() decides from the same row, so this has to be the locking read: with the
        // plain one a driver could declare themselves free while being handed a delivery.
        verify(driverRepository).findLockedByUserId(DRIVER_USER_ID);
        verify(driverRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("an unusable page size is a 400, not a 500 from PageRequest")
    void refusesAnUnusablePageSize() {
        assertThatThrownBy(() -> driverService().search(null, null, 0, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> driverService().search(null, null, -1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verify(driverRepository, never()).search(any(), any(), any());
    }

    @Test
    @DisplayName("a page of drivers resolves every current delivery in one batch, not one per row")
    void enrichesAPageWithoutPerRowQueries() {
        Driver first = driver();
        Driver second = driver();
        second.setId(4L);
        second.setUserId(44L);
        when(driverRepository.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of(first, second)));
        when(deliveryRepository.findOpenByDriverIds(anyList(), any()))
                .thenReturn(List.of(openDelivery(88L, DRIVER_ID, 7L), openDelivery(89L, 4L, 8L)));
        when(deliveryRepository.countByDriverIdsAndStatus(anyList(), any())).thenReturn(List.of());
        when(vehicleRepository.findAllById(anyList())).thenReturn(List.of());

        var page = driverService().search(null, null, 0, 20);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).currentDeliveryId()).isEqualTo(88L);
        assertThat(page.content().get(1).currentDeliveryId()).isEqualTo(89L);
        // Two drivers, three queries. A per-row lookup would be findOpenByDriverIds twice
        // and findAllById twice, which is the shape this guards against.
        verify(deliveryRepository).findOpenByDriverIds(anyList(), any());
        verify(deliveryRepository).countByDriverIdsAndStatus(anyList(), any());
        verify(vehicleRepository).findAllById(anyList());
    }

    private static Delivery openDelivery(long id, long driverId, long vehicleId) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        delivery.setDriverId(driverId);
        delivery.setVehicleId(vehicleId);
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        return delivery;
    }

    private static Driver driver() {
        Driver driver = new Driver();
        driver.setId(DRIVER_ID);
        driver.setUserId(DRIVER_USER_ID);
        driver.setFullName("Karim Ben Salah");
        driver.setLicenseNumber("TN-DL-102938");
        driver.setPhone("+21620123456");
        driver.setStatus(DriverStatus.AVAILABLE);
        return driver;
    }
}