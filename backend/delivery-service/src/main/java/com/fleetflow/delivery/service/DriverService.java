package com.fleetflow.delivery.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.delivery.dto.DriverResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.mapper.DriverMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverDeliveryCount;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@Service
public class DriverService {

    private final DriverRepository driverRepository;
    private final DeliveryRepository deliveryRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverMapper mapper;

    public DriverService(DriverRepository driverRepository,
            DeliveryRepository deliveryRepository,
            VehicleRepository vehicleRepository,
            DriverMapper mapper) {
        this.driverRepository = driverRepository;
        this.deliveryRepository = deliveryRepository;
        this.vehicleRepository = vehicleRepository;
        this.mapper = mapper;
    }

    public PageResponse<DriverResponse> search(String status, String search, int page, int size) {
        DriverStatus parsed = status == null || status.isBlank() ? null : DriverStatus.from(status);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "fullName"));
        Page<Driver> result = driverRepository.search(parsed, blankToNull(search), pageable);
        return PageResponse.from(result, enrich(result.getContent()));
    }

    public DriverResponse getForCaller(Long id) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Driver", id));
        SecurityUtils.requireSelfOrStaff(driver.getUserId());
        return enrich(List.of(driver)).get(0);
    }

    public DriverResponse getByUserId(Long userId) {
        return enrich(List.of(requireDriver(userId))).get(0);
    }

    /**
     * A driver declares when they start or end their shift. {@code ON_DELIVERY} is
     * owned by operations, so a driver can never grant themselves a job.
     */
    @Transactional
    public DriverResponse setOwnStatus(Long userId, String status) {
        Driver driver = requireDriver(userId);
        DriverStatus target = DriverStatus.from(status);
        if (target == DriverStatus.ON_DELIVERY) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "ON_DELIVERY is set by operations when a delivery is assigned");
        }
        if (target == DriverStatus.AVAILABLE
                && deliveryRepository.existsByDriverIdAndStatusIn(driver.getId(), DeliveryStatus.OPEN)) {
            throw new BusinessException(ErrorCode.CONFLICT, "The driver still has a delivery in progress");
        }
        driver.setStatus(target);
        return enrich(List.of(driver)).get(0);
    }

    private Driver requireDriver(Long userId) {
        return driverRepository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Driver", userId));
    }

    /**
     * Resolves the current delivery, the completed count and the vehicle plate with
     * three batch queries rather than one per driver on the page.
     */
    private List<DriverResponse> enrich(List<Driver> drivers) {
        if (drivers.isEmpty()) {
            return List.of();
        }
        List<Long> driverIds = drivers.stream().map(Driver::getId).toList();

        Map<Long, Delivery> current = deliveryRepository.findOpenByDriverIds(driverIds, DeliveryStatus.OPEN).stream()
                .collect(Collectors.toMap(Delivery::getDriverId, Function.identity(), (first, second) -> first));

        Map<Long, Long> completed = deliveryRepository
                .countByDriverIdsAndStatus(driverIds, DeliveryStatus.DELIVERED).stream()
                .collect(Collectors.toMap(DriverDeliveryCount::getDriverId, DriverDeliveryCount::getTotal));

        List<Long> vehicleIds = current.values().stream()
                .map(Delivery::getVehicleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> registrations = vehicleIds.isEmpty() ? Map.of()
                : vehicleRepository.findAllById(vehicleIds).stream()
                        .collect(Collectors.toMap(Vehicle::getId, Vehicle::getRegistrationNumber));

        return drivers.stream()
                .map(driver -> {
                    Delivery open = current.get(driver.getId());
                    String registration = open == null || open.getVehicleId() == null
                            ? null
                            : registrations.get(open.getVehicleId());
                    return mapper.toResponse(driver, open,
                            completed.getOrDefault(driver.getId(), 0L), registration);
                })
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
