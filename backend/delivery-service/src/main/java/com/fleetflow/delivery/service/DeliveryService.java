package com.fleetflow.delivery.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.event.payload.DeliveryStatusChangedPayload;
import com.fleetflow.common.event.payload.InventoryReservedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.InvalidStateTransitionException;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.SecurityUtils;
import com.fleetflow.delivery.client.CustomerContact;
import com.fleetflow.delivery.client.CustomerServiceClient;
import com.fleetflow.delivery.config.KafkaTopicProperties;
import com.fleetflow.delivery.config.WarehouseProperties;
import com.fleetflow.delivery.dto.CreateDeliveryRequest;
import com.fleetflow.delivery.dto.DeliveryCustomerContact;
import com.fleetflow.delivery.dto.DeliveryKpiResponse;
import com.fleetflow.delivery.dto.DeliveryResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.mapper.DeliveryMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final DeliveryRepository deliveryRepository;
    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DomainEventPublisher eventPublisher;
    private final KafkaTopicProperties topics;
    private final CustomerServiceClient customerServiceClient;
    private final WarehouseProperties warehouses;
    private final DeliveryMapper mapper;

    public DeliveryService(DeliveryRepository deliveryRepository,
            DriverRepository driverRepository,
            VehicleRepository vehicleRepository,
            DomainEventPublisher eventPublisher,
            KafkaTopicProperties topics,
            CustomerServiceClient customerServiceClient,
            WarehouseProperties warehouses,
            DeliveryMapper mapper) {
        this.deliveryRepository = deliveryRepository;
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.eventPublisher = eventPublisher;
        this.topics = topics;
        this.customerServiceClient = customerServiceClient;
        this.warehouses = warehouses;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ queries

    public PageResponse<DeliveryResponse> search(String status, Long driverId, Long vehicleId,
            Instant from, Instant to, int page, int size) {

        DeliveryStatus parsed = status == null || status.isBlank() ? null : DeliveryStatus.from(status);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Delivery> result = deliveryRepository.search(parsed, driverId, vehicleId, from, to, pageable);
        return PageResponse.from(result, result.getContent().stream().map(mapper::toResponse).toList());
    }

    public List<DeliveryResponse> findActive() {
        return deliveryRepository.findByStatusInOrderByCreatedAtDesc(DeliveryStatus.OPEN).stream()
                .map(mapper::toResponse)
                .toList();
    }

    public List<DeliveryResponse> findForDriver(Long userId) {
        Driver driver = requireDriverByUserId(userId);
        return deliveryRepository.findByDriverIdOrderByCreatedAtDesc(driver.getId(), PageRequest.of(0, 200)).stream()
                .map(mapper::toResponse)
                .toList();
    }

    public DeliveryResponse get(Long id) {
        return withDriverAndVehicle(deliveryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Delivery", id)));
    }

    /** Staff, the assigned driver, or the customer who placed the order. */
    public DeliveryResponse getVisibleToCaller(Long id) {
        Delivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Delivery", id));

        if (SecurityUtils.isStaff()) {
            return withDriverAndVehicle(delivery);
        }
        if (SecurityUtils.hasRole(FleetRole.DRIVER)) {
            requireAssignedDriver(delivery, SecurityUtils.requireUserId());
            return withDriverAndVehicle(delivery);
        }
        SecurityUtils.requireSelfOrStaff(delivery.getCustomerId());
        return withDriverAndVehicle(delivery);
    }

    public DeliveryCustomerContact getCustomerContact(Long id) {
        Delivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Delivery", id));
        if (!SecurityUtils.isStaff()) {
            requireAssignedDriver(delivery, SecurityUtils.requireUserId());
        }
        // Served from the snapshot: the driver must be able to call the customer even
        // when customer-service is down, which is exactly when they need it most.
        return new DeliveryCustomerContact(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getCustomerName(),
                delivery.getCustomerPhone(),
                null,
                delivery.getDeliveryAddress(),
                delivery.getCity(),
                delivery.getPostalCode());
    }

    public DeliveryKpiResponse kpi() {
        return new DeliveryKpiResponse(
                deliveryRepository.countByStatusIn(DeliveryStatus.OPEN),
                driverRepository.countByStatus(DriverStatus.AVAILABLE),
                driverRepository.countByStatus(DriverStatus.ON_DELIVERY),
                vehicleRepository.countByStatus(VehicleStatus.AVAILABLE),
                deliveryRepository.countByStatusAndCompletedAtGreaterThanEqual(DeliveryStatus.DELIVERED,
                        Instant.now().truncatedTo(ChronoUnit.DAYS)),
                deliveryRepository.countByStatus(DeliveryStatus.FAILED));
    }

    // ------------------------------------------------------------------ writes

    /** Manual creation for an order that has no delivery yet. */
    @Transactional
    public DeliveryResponse create(CreateDeliveryRequest request) {
        if (deliveryRepository.existsByOrderId(request.orderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "A delivery already exists for this order");
        }
        Delivery delivery = new Delivery();
        delivery.setOrderId(request.orderId());
        delivery.setCustomerId(request.customerId());
        delivery.setStatus(DeliveryStatus.CREATED);
        delivery.setPickupAddress(request.pickupAddress());
        delivery.setDeliveryAddress(request.deliveryAddress());
        delivery.setCity(request.city());
        delivery.setPostalCode(request.postalCode());
        delivery.setCustomerName(request.customerName());
        delivery.setCustomerPhone(request.customerPhone());
        delivery.setScheduledAt(request.scheduledAt());
        deliveryRepository.save(delivery);

        log.info("Created delivery {} for order {} [correlationId={}]", delivery.getId(), delivery.getOrderId(),
                CorrelationId.getOrCreate());
        return mapper.toResponse(delivery);
    }

    /** Opens the one delivery an order is entitled to when its stock has been reserved. */
    @Transactional
    public void createFromInventoryReserved(InventoryReservedPayload payload) {
        if (deliveryRepository.existsByOrderId(payload.orderId())) {
            log.info("Order {} already has a delivery, ignoring duplicate reservation [correlationId={}]",
                    payload.orderId(), CorrelationId.getOrCreate());
            return;
        }
        CustomerContact contact = customerServiceClient.getContactByUserId(payload.customerId());

        Delivery delivery = new Delivery();
        delivery.setOrderId(payload.orderId());
        delivery.setCustomerId(payload.customerId());
        delivery.setStatus(DeliveryStatus.CREATED);
        delivery.setPickupAddress(warehouses.getDefaultPickupAddress());
        delivery.setDeliveryAddress(contact.address());
        delivery.setCity(contact.city() != null ? contact.city() : warehouses.getDefaultPickupCity());
        delivery.setPostalCode(contact.postalCode() != null ? contact.postalCode()
                : warehouses.getDefaultPickupPostalCode());
        delivery.setCustomerName(fullName(contact));
        delivery.setCustomerPhone(contact.phone());
        deliveryRepository.save(delivery);

        log.info("Opened delivery {} for order {} from warehouse {} [correlationId={}]", delivery.getId(),
                payload.orderId(), payload.warehouseName(), CorrelationId.getOrCreate());
    }

    @Transactional
    public DeliveryResponse assign(Long driverId, Long vehicleId, Long deliveryId) {
        Delivery delivery = requireDelivery(deliveryId);
        if (delivery.getStatus() != DeliveryStatus.CREATED) {
            // A FAILED delivery is requeued through the dedicated staff endpoint, which
            // keeps the reassignment path free of hidden state fixes.
            throw new InvalidStateTransitionException("Delivery", deliveryId,
                    delivery.getStatus().name(), DeliveryStatus.ASSIGNED.name());
        }

        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> ResourceNotFoundException.of("Driver", driverId));
        if (driver.getStatus() != DriverStatus.AVAILABLE
                || deliveryRepository.existsByDriverIdAndStatusIn(driverId, DeliveryStatus.OPEN)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Driver is not available");
        }

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Vehicle", vehicleId));
        if (vehicle.getStatus() != VehicleStatus.AVAILABLE
                || deliveryRepository.existsByVehicleIdAndStatusIn(vehicleId, DeliveryStatus.OPEN)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Vehicle is not available");
        }

        delivery.setDriverId(driverId);
        delivery.setVehicleId(vehicleId);
        delivery.transitionTo(DeliveryStatus.ASSIGNED);
        driver.setStatus(DriverStatus.ON_DELIVERY);
        vehicle.setStatus(VehicleStatus.IN_USE);

        String topic = topics.getDeliveryAssigned();
        eventPublisher.publish(topic, String.valueOf(delivery.getOrderId()),
                EventEnvelope.of(EventTypes.DELIVERY_ASSIGNED, topic,
                        new DeliveryAssignedPayload(delivery.getId(), delivery.getOrderId(), delivery.getCustomerId(),
                                driverId, driver.getFullName(), vehicleId, vehicle.getRegistrationNumber())));

        log.info("Assigned delivery {} to driver {} with vehicle {} [correlationId={}]", deliveryId, driverId,
                vehicleId, CorrelationId.getOrCreate());
        return mapper.toResponse(delivery, driver, vehicle);
    }

    @Transactional
    public DeliveryResponse changeStatus(Long deliveryId, String status, String reason, Long actorUserId,
            FleetRole actorRole) {

        DeliveryStatus target = DeliveryStatus.from(status);
        Delivery delivery = requireDelivery(deliveryId);
        Driver driver = delivery.getDriverId() == null ? null
                : driverRepository.findById(delivery.getDriverId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Driver", delivery.getDriverId()));
        // Reject an unauthorised actor before doing any further work on their behalf.
        requireActor(delivery, driver, actorUserId, actorRole);

        Vehicle vehicle = delivery.getVehicleId() == null ? null
                : vehicleRepository.findById(delivery.getVehicleId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Vehicle", delivery.getVehicleId()));

        DeliveryStatus previous = delivery.getStatus();
        delivery.transitionTo(target);

        Instant now = Instant.now();
        if (target == DeliveryStatus.IN_TRANSIT && delivery.getStartedAt() == null) {
            delivery.setStartedAt(now);
        }
        if (target == DeliveryStatus.DELIVERED) {
            delivery.setCompletedAt(now);
            delivery.setProofOfDelivery(reason);
        }
        if (target == DeliveryStatus.FAILED) {
            delivery.setFailureReason(reason);
        }
        if (target.isTerminal()) {
            // DELIVERED, FAILED and CANCELLED all end the job, so the crew goes back
            // to the pool; FAILED stays requeueable through requeue().
            release(driver, vehicle);
        }

        publishTransition(delivery, driver, previous, target, reason, now);

        log.info("Delivery {} moved from {} to {} [correlationId={}]", deliveryId, previous, target,
                CorrelationId.getOrCreate());
        return mapper.toResponse(delivery, driver, vehicle);
    }

    /**
     * Puts a failed delivery back on the road with the same crew. The driver and
     * vehicle had been released when the attempt failed, so they are claimed again
     * and checked for conflicts exactly like a fresh assignment.
     */
    @Transactional
    public DeliveryResponse requeue(Long deliveryId, String reason) {
        Delivery delivery = requireDelivery(deliveryId);
        if (delivery.getStatus() != DeliveryStatus.FAILED) {
            throw new InvalidStateTransitionException("Delivery", deliveryId,
                    delivery.getStatus().name(), DeliveryStatus.ASSIGNED.name());
        }
        Driver driver = delivery.getDriverId() == null ? null
                : driverRepository.findById(delivery.getDriverId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Driver", delivery.getDriverId()));
        Vehicle vehicle = delivery.getVehicleId() == null ? null
                : vehicleRepository.findById(delivery.getVehicleId())
                        .orElseThrow(() -> ResourceNotFoundException.of("Vehicle", delivery.getVehicleId()));

        if (driver != null) {
            if (driver.getStatus() != DriverStatus.AVAILABLE
                    || deliveryRepository.existsByDriverIdAndStatusIn(driver.getId(), DeliveryStatus.OPEN)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Driver is not available");
            }
            driver.setStatus(DriverStatus.ON_DELIVERY);
        }
        if (vehicle != null) {
            if (vehicle.getStatus() != VehicleStatus.AVAILABLE
                    || deliveryRepository.existsByVehicleIdAndStatusIn(vehicle.getId(), DeliveryStatus.OPEN)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Vehicle is not available");
            }
            vehicle.setStatus(VehicleStatus.IN_USE);
        }

        delivery.transitionTo(DeliveryStatus.ASSIGNED);

        log.info("Requeued failed delivery {} with reason '{}' [correlationId={}]", deliveryId, reason,
                CorrelationId.getOrCreate());
        return mapper.toResponse(delivery, driver, vehicle);
    }

    // ----------------------------------------------------------------- helpers

    private void requireActor(Delivery delivery, Driver driver, Long actorUserId, FleetRole actorRole) {
        if (actorRole == FleetRole.DRIVER) {
            if (driver == null || !driver.getUserId().equals(actorUserId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "This delivery is not assigned to you");
            }
            return;
        }
        if (actorRole != FleetRole.ADMIN && actorRole != FleetRole.OPERATIONS) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You may not change the status of a delivery");
        }
    }

    private void requireAssignedDriver(Delivery delivery, Long userId) {
        if (delivery.getDriverId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This delivery is not assigned to you");
        }
        Driver driver = driverRepository.findById(delivery.getDriverId())
                .orElseThrow(() -> ResourceNotFoundException.of("Driver", delivery.getDriverId()));
        if (!driver.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This delivery is not assigned to you");
        }
    }

    private void release(Driver driver, Vehicle vehicle) {
        if (driver != null) {
            driver.setStatus(DriverStatus.AVAILABLE);
        }
        if (vehicle != null) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
        }
    }

    private void publishTransition(Delivery delivery, Driver driver, DeliveryStatus previous, DeliveryStatus target,
            String reason, Instant now) {

        String key = String.valueOf(delivery.getOrderId());
        Long driverId = driver != null ? driver.getId() : delivery.getDriverId();

        switch (target) {
            case PICKED_UP -> publishStatusChanged(delivery, driverId, previous, target, reason, key,
                    topics.getDeliveryPickedUp(), EventTypes.DELIVERY_PICKED_UP);
            case IN_TRANSIT -> publishStatusChanged(delivery, driverId, previous, target, reason, key,
                    topics.getDeliveryStarted(), EventTypes.DELIVERY_STARTED);
            case FAILED -> publishStatusChanged(delivery, driverId, previous, target, reason, key,
                    topics.getDeliveryFailed(), EventTypes.DELIVERY_FAILED);
            case CANCELLED -> publishStatusChanged(delivery, driverId, previous, target, reason, key,
                    topics.getDeliveryCancelled(), EventTypes.DELIVERY_CANCELLED);
            case DELIVERED -> {
                String topic = topics.getDeliveryCompleted();
                eventPublisher.publish(topic, key, EventEnvelope.of(EventTypes.DELIVERY_COMPLETED, topic,
                        new DeliveryCompletedPayload(delivery.getId(), delivery.getOrderId(), delivery.getCustomerId(),
                                driverId, now, delivery.getProofOfDelivery())));
            }
            default -> {
                // ASSIGNED and CREATED carry no notification of their own.
            }
        }
    }

    private void publishStatusChanged(Delivery delivery, Long driverId, DeliveryStatus previous, DeliveryStatus target,
            String reason, String key, String topic, String eventType) {

        eventPublisher.publish(topic, key, EventEnvelope.of(eventType, topic,
                new DeliveryStatusChangedPayload(delivery.getId(), delivery.getOrderId(), delivery.getCustomerId(),
                        driverId, previous.name(), target.name(), reason)));
    }

    private DeliveryResponse withDriverAndVehicle(Delivery delivery) {
        Driver driver = delivery.getDriverId() == null ? null : driverRepository.findById(delivery.getDriverId())
                .orElse(null);
        Vehicle vehicle = delivery.getVehicleId() == null ? null : vehicleRepository.findById(delivery.getVehicleId())
                .orElse(null);
        return mapper.toResponse(delivery, driver, vehicle);
    }

    private Delivery requireDelivery(Long id) {
        return deliveryRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Delivery", id));
    }

    private Driver requireDriverByUserId(Long userId) {
        return driverRepository.findByUserId(userId).orElseThrow(() -> ResourceNotFoundException.of("Driver", userId));
    }

    private static String fullName(CustomerContact contact) {
        if (contact.firstName() == null && contact.lastName() == null) {
            return null;
        }
        return (contact.firstName() == null ? "" : contact.firstName() + " ")
                + (contact.lastName() == null ? "" : contact.lastName());
    }
}
