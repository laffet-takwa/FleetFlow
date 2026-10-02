package com.fleetflow.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.delivery.dto.DriverResponse;
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
        when(driverRepository.findByUserId(DRIVER_USER_ID)).thenReturn(Optional.of(driver()));

        assertThatThrownBy(() -> driverService().setOwnStatus(DRIVER_USER_ID, "ON_DELIVERY"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
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
