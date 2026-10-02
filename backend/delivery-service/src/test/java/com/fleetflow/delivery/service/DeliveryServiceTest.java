package com.fleetflow.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.event.DomainEventPublisher;
import com.fleetflow.common.event.EventEnvelope;
import com.fleetflow.common.event.EventTypes;
import com.fleetflow.common.event.payload.DeliveryAssignedPayload;
import com.fleetflow.common.event.payload.DeliveryCompletedPayload;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.InvalidStateTransitionException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;
import com.fleetflow.delivery.client.CustomerServiceClient;
import com.fleetflow.delivery.config.KafkaTopicProperties;
import com.fleetflow.delivery.config.WarehouseProperties;
import com.fleetflow.delivery.dto.DeliveryResponse;
import com.fleetflow.delivery.entity.Delivery;
import com.fleetflow.delivery.entity.DeliveryStatus;
import com.fleetflow.delivery.entity.Driver;
import com.fleetflow.delivery.entity.DriverStatus;
import com.fleetflow.delivery.entity.Vehicle;
import com.fleetflow.delivery.entity.VehicleStatus;
import com.fleetflow.delivery.entity.VehicleType;
import com.fleetflow.delivery.mapper.DeliveryMapper;
import com.fleetflow.delivery.repository.DeliveryRepository;
import com.fleetflow.delivery.repository.DriverRepository;
import com.fleetflow.delivery.repository.VehicleRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryService")
class DeliveryServiceTest {

    private static final long DRIVER_ID = 3L;
    private static final long DRIVER_USER_ID = 3L;
    private static final long OTHER_DRIVER_USER_ID = 4L;
    private static final long VEHICLE_ID = 7L;
    private static final long DELIVERY_ID = 12L;
    private static final long ORDER_ID = 42L;

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private DriverRepository driverRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private CustomerServiceClient customerServiceClient;

    @Captor
    private ArgumentCaptor<String> topicCaptor;
    @Captor
    private ArgumentCaptor<EventEnvelope<Object>> envelopeCaptor;

    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        KafkaTopicProperties topics = new KafkaTopicProperties();
        topics.setDeliveryAssigned("delivery.assigned");
        topics.setDeliveryPickedUp("delivery.picked-up");
        topics.setDeliveryStarted("delivery.started");
        topics.setDeliveryCompleted("delivery.completed");
        topics.setDeliveryFailed("delivery.failed");
        topics.setDeliveryCancelled("delivery.cancelled");

        deliveryService = new DeliveryService(deliveryRepository, driverRepository, vehicleRepository,
                eventPublisher, topics, customerServiceClient, new WarehouseProperties(), new DeliveryMapper());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("assigning a driver and a vehicle locks both and publishes delivery.assigned")
    void assignPublishesAndLocksResources() {
        Delivery delivery = delivery(DeliveryStatus.CREATED);
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.AVAILABLE);
        Vehicle vehicle = vehicle(VEHICLE_ID, VehicleStatus.AVAILABLE);
        givenDelivery(delivery, driver, vehicle);

        DeliveryResponse response = deliveryService.assign(DRIVER_ID, VEHICLE_ID, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ASSIGNED");
        assertThat(response.driverId()).isEqualTo(DRIVER_ID);
        assertThat(response.driverName()).isEqualTo("Karim Ben Salah");
        assertThat(response.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(response.vehicleRegistration()).isEqualTo("123 تونس 4567");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.ASSIGNED);
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.ON_DELIVERY);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);

        verify(eventPublisher).publish(topicCaptor.capture(), eq(String.valueOf(ORDER_ID)), envelopeCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("delivery.assigned");
        assertThat(envelopeCaptor.getValue().eventType()).isEqualTo(EventTypes.DELIVERY_ASSIGNED);
        assertThat(envelopeCaptor.getValue().topic()).isEqualTo("delivery.assigned");

        DeliveryAssignedPayload payload =
                (DeliveryAssignedPayload) envelopeCaptor.getValue().payload();
        assertThat(payload.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(payload.orderId()).isEqualTo(ORDER_ID);
        assertThat(payload.driverId()).isEqualTo(DRIVER_ID);
        assertThat(payload.driverName()).isEqualTo("Karim Ben Salah");
        assertThat(payload.vehicleRegistration()).isEqualTo("123 تونس 4567");
    }

    @Test
    @DisplayName("an OFFLINE driver cannot be assigned")
    void rejectsOfflineDriver() {
        Delivery delivery = delivery(DeliveryStatus.CREATED);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID))
                .thenReturn(Optional.of(driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.OFFLINE)));

        assertThatThrownBy(() -> deliveryService.assign(DRIVER_ID, VEHICLE_ID, DELIVERY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("Driver is not available");

        verify(vehicleRepository, never()).findById(any());
        verify(eventPublisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a vehicle already IN_USE cannot be assigned")
    void rejectsVehicleInUse() {
        Delivery delivery = delivery(DeliveryStatus.CREATED);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID))
                .thenReturn(Optional.of(driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.AVAILABLE)));
        when(deliveryRepository.existsByDriverIdAndStatusIn(DRIVER_ID, DeliveryStatus.OPEN)).thenReturn(false);
        when(vehicleRepository.findById(VEHICLE_ID))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, VehicleStatus.IN_USE)));

        assertThatThrownBy(() -> deliveryService.assign(DRIVER_ID, VEHICLE_ID, DELIVERY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("Vehicle is not available");

        verify(eventPublisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a driver already on an open delivery cannot take another one")
    void rejectsDriverWithActiveDelivery() {
        Delivery delivery = delivery(DeliveryStatus.CREATED);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID))
                .thenReturn(Optional.of(driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.AVAILABLE)));
        when(deliveryRepository.existsByDriverIdAndStatusIn(DRIVER_ID, DeliveryStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> deliveryService.assign(DRIVER_ID, VEHICLE_ID, DELIVERY_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("Driver is not available");

        verify(vehicleRepository, never()).findById(any());
    }

    @Test
    @DisplayName("DELIVERED to IN_TRANSIT is refused")
    void rejectsDeliveredToInTransit() {
        Delivery delivery = delivery(DeliveryStatus.DELIVERED);
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.AVAILABLE);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findById(VEHICLE_ID))
                .thenReturn(Optional.of(vehicle(VEHICLE_ID, VehicleStatus.AVAILABLE)));

        assertThatThrownBy(() -> deliveryService.changeStatus(DELIVERY_ID, "IN_TRANSIT", null,
                DRIVER_USER_ID, FleetRole.DRIVER))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("DELIVERED")
                .hasMessageContaining("IN_TRANSIT");

        verify(eventPublisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a driver cannot act on another driver's delivery")
    void rejectsDriverActingOnForeignDelivery() {
        Delivery delivery = delivery(DeliveryStatus.ASSIGNED);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID))
                .thenReturn(Optional.of(driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.ON_DELIVERY)));

        assertThatThrownBy(() -> deliveryService.changeStatus(DELIVERY_ID, "PICKED_UP", null,
                OTHER_DRIVER_USER_ID, FleetRole.DRIVER))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(eventPublisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("completing a delivery frees the driver and the vehicle and publishes delivery.completed")
    void completingFreesResourcesAndPublishes() {
        Delivery delivery = delivery(DeliveryStatus.IN_TRANSIT);
        delivery.setStartedAt(Instant.now().minusSeconds(3600));
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.ON_DELIVERY);
        Vehicle vehicle = vehicle(VEHICLE_ID, VehicleStatus.IN_USE);
        givenDelivery(delivery, driver, vehicle);
        authenticate(DRIVER_USER_ID, FleetRole.DRIVER);

        DeliveryResponse response = deliveryService.changeStatus(DELIVERY_ID, "DELIVERED",
                "Signed by the concierge", DRIVER_USER_ID, FleetRole.DRIVER);

        assertThat(response.status()).isEqualTo("DELIVERED");
        assertThat(response.completedAt()).isNotNull();
        assertThat(response.proofOfDelivery()).isEqualTo("Signed by the concierge");
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);

        verify(eventPublisher).publish(topicCaptor.capture(), eq(String.valueOf(ORDER_ID)), envelopeCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("delivery.completed");
        assertThat(envelopeCaptor.getValue().eventType()).isEqualTo(EventTypes.DELIVERY_COMPLETED);

        DeliveryCompletedPayload payload = (DeliveryCompletedPayload) envelopeCaptor.getValue().payload();
        assertThat(payload.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(payload.orderId()).isEqualTo(ORDER_ID);
        assertThat(payload.driverId()).isEqualTo(DRIVER_ID);
        assertThat(payload.completedAt()).isNotNull();
        assertThat(payload.proofOfDelivery()).isEqualTo("Signed by the concierge");
    }

    @Test
    @DisplayName("a failing attempt frees the crew and publishes delivery.failed with the reason")
    void failingFreesResourcesAndCarriesTheReason() {
        Delivery delivery = delivery(DeliveryStatus.IN_TRANSIT);
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.ON_DELIVERY);
        Vehicle vehicle = vehicle(VEHICLE_ID, VehicleStatus.IN_USE);
        givenDelivery(delivery, driver, vehicle);

        DeliveryResponse response = deliveryService.changeStatus(DELIVERY_ID, "FAILED",
                "Customer absent", DRIVER_USER_ID, FleetRole.DRIVER);

        assertThat(response.failureReason()).isEqualTo("Customer absent");
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.AVAILABLE);
        verify(eventPublisher).publish(topicCaptor.capture(), eq(String.valueOf(ORDER_ID)), envelopeCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("delivery.failed");
    }

    @Test
    @DisplayName("IN_TRANSIT records the start time and publishes delivery.started")
    void inTransitRecordsStartTime() {
        Delivery delivery = delivery(DeliveryStatus.PICKED_UP);
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.ON_DELIVERY);
        Vehicle vehicle = vehicle(VEHICLE_ID, VehicleStatus.IN_USE);
        givenDelivery(delivery, driver, vehicle);

        DeliveryResponse response = deliveryService.changeStatus(DELIVERY_ID, "IN_TRANSIT", null,
                DRIVER_USER_ID, FleetRole.DRIVER);

        assertThat(response.startedAt()).isNotNull();
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.ON_DELIVERY);
        verify(eventPublisher).publish(topicCaptor.capture(), eq(String.valueOf(ORDER_ID)), envelopeCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("delivery.started");
    }

    @Test
    @DisplayName("a delivery can only be assigned while it is CREATED")
    void rejectsAssigningAnAlreadyAssignedDelivery() {
        Delivery delivery = delivery(DeliveryStatus.ASSIGNED);
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));

        assertThatThrownBy(() -> deliveryService.assign(DRIVER_ID, VEHICLE_ID, DELIVERY_ID))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(driverRepository, never()).findById(any());
    }

    @Test
    @DisplayName("requeue claims the crew again and moves FAILED back to ASSIGNED")
    void requeueReclaimsTheCrew() {
        Delivery delivery = delivery(DeliveryStatus.FAILED);
        delivery.setFailureReason("Customer absent");
        Driver driver = driver(DRIVER_ID, DRIVER_USER_ID, DriverStatus.AVAILABLE);
        Vehicle vehicle = vehicle(VEHICLE_ID, VehicleStatus.AVAILABLE);
        givenDelivery(delivery, driver, vehicle);

        DeliveryResponse response = deliveryService.requeue(DELIVERY_ID, "Second attempt");

        assertThat(response.status()).isEqualTo("ASSIGNED");
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.ON_DELIVERY);
        assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.IN_USE);
        assertThat(response.failureReason()).isEqualTo("Customer absent");
    }

    // ------------------------------------------------------------------ helpers

    private void givenDelivery(Delivery delivery, Driver driver, Vehicle vehicle) {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(driverRepository.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(vehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle));
    }

    private static Delivery delivery(DeliveryStatus status) {
        Delivery delivery = new Delivery();
        delivery.setId(DELIVERY_ID);
        delivery.setOrderId(ORDER_ID);
        delivery.setCustomerId(8L);
        delivery.setDriverId(DRIVER_ID);
        delivery.setVehicleId(VEHICLE_ID);
        delivery.setStatus(status);
        delivery.setPickupAddress("Zone Industrielle El Mghira, Tunis 1000");
        delivery.setDeliveryAddress("Rue Habib Bourguiba 12");
        delivery.setCity("Tunis");
        delivery.setPostalCode("1000");
        delivery.setCustomerName("Amel Trabelsi");
        delivery.setCustomerPhone("+21620111222");
        return delivery;
    }

    private static Driver driver(Long id, Long userId, DriverStatus status) {
        Driver driver = new Driver();
        driver.setId(id);
        driver.setUserId(userId);
        driver.setFullName("Karim Ben Salah");
        driver.setLicenseNumber("TN-DL-102938");
        driver.setPhone("+21620123456");
        driver.setStatus(status);
        return driver;
    }

    private static Vehicle vehicle(Long id, VehicleStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setRegistrationNumber("123 تونس 4567");
        vehicle.setType(VehicleType.VAN);
        vehicle.setCapacity(800);
        vehicle.setStatus(status);
        return vehicle;
    }

    private static void authenticate(Long userId, FleetRole role) {
        JwtPrincipal principal = new JwtPrincipal(userId, "driver1@fleetflow.local", role);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role.authority()))));
    }
}
