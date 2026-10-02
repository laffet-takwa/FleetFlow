package com.fleetflow.delivery.service;

import java.util.List;
import java.util.Map;
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
import com.fleetflow.delivery.dto.CreateVehicleRequest;
import com.fleetflow.delivery.dto.UpdateVehicleRequest;
import com.fleetflow.delivery.dto.VehicleResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;
import com.fleetflow.delivery.mapper.VehicleMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final DeliveryRepository deliveryRepository;
    private final DriverRepository driverRepository;
    private final VehicleMapper mapper;

    public VehicleService(VehicleRepository vehicleRepository,
            DeliveryRepository deliveryRepository,
            DriverRepository driverRepository,
            VehicleMapper mapper) {
        this.vehicleRepository = vehicleRepository;
        this.deliveryRepository = deliveryRepository;
        this.driverRepository = driverRepository;
        this.mapper = mapper;
    }

    public PageResponse<VehicleResponse> search(String status, String type, String search, int page, int size) {
        VehicleStatus parsedStatus = status == null || status.isBlank() ? null : VehicleStatus.from(status);
        VehicleType parsedType = type == null || type.isBlank() ? null : VehicleType.from(type);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "registrationNumber"));
        Page<Vehicle> result = vehicleRepository.search(parsedStatus, parsedType, blankToNull(search), pageable);
        return PageResponse.from(result, enrich(result.getContent()));
    }

    public VehicleResponse get(Long id) {
        return enrich(List.of(requireVehicle(id))).get(0);
    }

    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        if (vehicleRepository.existsByRegistrationNumber(request.registrationNumber())) {
            throw new BusinessException(ErrorCode.CONFLICT, "A vehicle with this registration already exists");
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setRegistrationNumber(request.registrationNumber());
        vehicle.setType(VehicleType.from(request.type()));
        vehicle.setCapacity(request.capacity());
        vehicle.setStatus(request.status() == null || request.status().isBlank()
                ? VehicleStatus.AVAILABLE
                : VehicleStatus.from(request.status()));
        vehicleRepository.save(vehicle);
        return mapper.toResponse(vehicle, null, null);
    }

    @Transactional
    public VehicleResponse update(Long id, UpdateVehicleRequest request) {
        Vehicle vehicle = requireVehicle(id);
        if (!vehicle.getRegistrationNumber().equals(request.registrationNumber())
                && vehicleRepository.existsByRegistrationNumber(request.registrationNumber())) {
            throw new BusinessException(ErrorCode.CONFLICT, "A vehicle with this registration already exists");
        }
        vehicle.setRegistrationNumber(request.registrationNumber());
        vehicle.setType(VehicleType.from(request.type()));
        vehicle.setCapacity(request.capacity());
        vehicle.setStatus(VehicleStatus.from(request.status()));
        return mapper.toResponse(vehicle, null, null);
    }

    private Vehicle requireVehicle(Long id) {
        return vehicleRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Vehicle", id));
    }

    private List<VehicleResponse> enrich(List<Vehicle> vehicles) {
        if (vehicles.isEmpty()) {
            return List.of();
        }
        List<Long> vehicleIds = vehicles.stream().map(Vehicle::getId).toList();

        Map<Long, Delivery> current = deliveryRepository.findOpenByVehicleIds(vehicleIds, DeliveryStatus.OPEN)
                .stream()
                .collect(Collectors.toMap(Delivery::getVehicleId, Function.identity(), (first, second) -> first));

        List<Long> driverIds = current.values().stream()
                .map(Delivery::getDriverId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Driver> drivers = driverIds.isEmpty() ? Map.of()
                : driverRepository.findAllById(driverIds).stream()
                        .collect(Collectors.toMap(Driver::getId, Function.identity()));

        return vehicles.stream()
                .map(vehicle -> {
                    Delivery open = current.get(vehicle.getId());
                    Driver driver = open == null || open.getDriverId() == null
                            ? null
                            : drivers.get(open.getDriverId());
                    return mapper.toResponse(vehicle, driver, open);
                })
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
