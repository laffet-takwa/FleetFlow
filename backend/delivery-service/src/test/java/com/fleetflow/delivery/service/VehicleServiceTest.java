package com.fleetflow.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;
import com.fleetflow.delivery.mapper.VehicleMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("VehicleService")
class VehicleServiceTest {

    private static final long FIRST_VEHICLE = 7L;
    private static final long SECOND_VEHICLE = 8L;
    private static final long DRIVER_ID = 3L;

    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DriverRepository driverRepository;

    private VehicleService vehicleService() {
        return new VehicleService(vehicleRepository, deliveryRepository, driverRepository, new VehicleMapper());
    }

    @Test
    @DisplayName("an unusable page size is a 400, not a 500 from PageRequest")
    void refusesAnUnusablePageSize() {
        assertThatThrownBy(() -> vehicleService().search(null, null, null, 0, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> vehicleService().search(null, null, null, 0, 5_000_000))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verify(vehicleRepository, never()).search(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a page of vehicles resolves every current driver in one batch, not one per row")
    void enrichesAPageWithoutPerRowQueries() {
        when(vehicleRepository.search(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(vehicle(FIRST_VEHICLE), vehicle(SECOND_VEHICLE))));
        when(deliveryRepository.findOpenByVehicleIds(anyList(), any()))
                .thenReturn(List.of(openDelivery(90L, FIRST_VEHICLE, DRIVER_ID), openDelivery(91L, SECOND_VEHICLE, 4L)));
        when(driverRepository.findAllById(anyList())).thenReturn(List.of(driver(DRIVER_ID), driver(4L)));

        var page = vehicleService().search(null, null, null, 0, 20);

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).currentDeliveryId()).isEqualTo(90L);
        assertThat(page.content().get(1).currentDeliveryId()).isEqualTo(91L);
        verify(deliveryRepository).findOpenByVehicleIds(anyList(), any());
        verify(driverRepository).findAllById(anyList());
    }

    private static Delivery openDelivery(long id, long vehicleId, long driverId) {
        Delivery delivery = new Delivery();
        delivery.setId(id);
        delivery.setVehicleId(vehicleId);
        delivery.setDriverId(driverId);
        delivery.setStatus(DeliveryStatus.IN_TRANSIT);
        return delivery;
    }

    private static Vehicle vehicle(long id) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setRegistrationNumber(id + " تونس 4567");
        vehicle.setType(VehicleType.VAN);
        vehicle.setCapacity(800);
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        return vehicle;
    }

    private static Driver driver(long id) {
        Driver driver = new Driver();
        driver.setId(id);
        driver.setUserId(id + 30);
        driver.setFullName("Karim Ben Salah");
        driver.setLicenseNumber("TN-DL-" + id);
        driver.setPhone("+21620123456");
        driver.setStatus(DriverStatus.ON_DELIVERY);
        return driver;
    }
}