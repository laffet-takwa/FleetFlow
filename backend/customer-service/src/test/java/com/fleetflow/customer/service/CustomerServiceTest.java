package com.fleetflow.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtPrincipal;

import com.fleetflow.customer.dto.UpdateProfileRequest;
import com.fleetflow.customer.entity.Customer;
import com.fleetflow.customer.repository.CustomerRepository;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository repository;

    @InjectMocks
    private CustomerService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void provisionsSkeletonOnFirstCallAndReusesItOnTheSecond() {
        authenticate(8L, "c@fleetflow.local", FleetRole.CUSTOMER);

        // Behaves like a real repository: nothing stored until the skeleton is saved.
        AtomicReference<Customer> stored = new AtomicReference<>();
        when(repository.findByUserId(8L)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            customer.setId(8L);
            stored.set(customer);
            return customer;
        });

        Customer first = service.getOrCreateCurrent();

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(repository).saveAndFlush(captor.capture());
        Customer skeleton = captor.getValue();
        assertThat(skeleton.getUserId()).isEqualTo(8L);
        assertThat(skeleton.getEmail()).isEqualTo("c@fleetflow.local");
        assertThat(skeleton.getFirstName()).isEmpty();
        assertThat(skeleton.getLastName()).isEmpty();
        assertThat(skeleton.getPhone()).isEmpty();
        assertThat(skeleton.getAddress()).isNull();
        assertThat(skeleton.getCity()).isNull();
        assertThat(skeleton.getPostalCode()).isNull();
        assertThat(first).isSameAs(skeleton);

        Customer second = service.getOrCreateCurrent();

        assertThat(second).isSameAs(skeleton);
        verify(repository, times(1)).saveAndFlush(any(Customer.class));
    }

    @Test
    void reusesAnExistingProfileWithoutWriting() {
        authenticate(9L, "customer2@fleetflow.local", FleetRole.CUSTOMER);
        Customer existing = customer(9L, 9L, "Ahmed", "Trabelsi");
        when(repository.findByUserId(9L)).thenReturn(Optional.of(existing));

        assertThat(service.getOrCreateCurrent()).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any(Customer.class));
    }

    @Test
    void updateAppliesOnlyTheFieldsInTheRequest() {
        authenticate(8L, "customer1@fleetflow.local", FleetRole.CUSTOMER);
        Customer existing = customer(8L, 8L, "Yasmine", "Ben Salah");
        existing.setEmail("customer1@fleetflow.local");
        when(repository.findByUserId(8L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        Customer updated = service.updateCurrentProfile(new UpdateProfileRequest(
                "Yasmine", "Ben Salah Jr", "+21620998877", "5 Rue de Rome", "Ariana", "1014"));

        assertThat(updated.getFirstName()).isEqualTo("Yasmine");
        assertThat(updated.getLastName()).isEqualTo("Ben Salah Jr");
        assertThat(updated.getPhone()).isEqualTo("+21620998877");
        assertThat(updated.getAddress()).isEqualTo("5 Rue de Rome");
        assertThat(updated.getCity()).isEqualTo("Ariana");
        assertThat(updated.getPostalCode()).isEqualTo("1014");
        // Identity fields are not part of the request, so they must survive untouched.
        assertThat(updated.getId()).isEqualTo(8L);
        assertThat(updated.getUserId()).isEqualTo(8L);
        assertThat(updated.getEmail()).isEqualTo("customer1@fleetflow.local");
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void nonStaffNonOwnerIsRejectedAsForbidden() {
        authenticate(8L, "customer1@fleetflow.local", FleetRole.CUSTOMER);
        when(repository.findById(9L)).thenReturn(Optional.of(customer(9L, 9L, "Ahmed", "Trabelsi")));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.getById(9L))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void staffCanReadAnyCustomer() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);
        Customer other = customer(9L, 9L, "Ahmed", "Trabelsi");
        when(repository.findById(9L)).thenReturn(Optional.of(other));

        assertThat(service.getById(9L)).isSameAs(other);
    }

    @Test
    void ownerCanReadTheirOwnRow() {
        authenticate(8L, "customer1@fleetflow.local", FleetRole.CUSTOMER);
        Customer own = customer(8L, 8L, "Yasmine", "Ben Salah");
        when(repository.findById(8L)).thenReturn(Optional.of(own));

        assertThat(service.getById(8L)).isSameAs(own);
    }

    @Test
    void unknownCustomerIsNotFound() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getById(404L))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void contactLookupIsKeyedByAuthUserIdAndFailsWhenAbsent() {
        Customer owned = customer(9L, 12L, "Ines", "Bouazizi");
        when(repository.findByUserId(12L)).thenReturn(Optional.of(owned));
        assertThat(service.getContactByUserId(12L)).isSameAs(owned);

        when(repository.findByUserId(99L)).thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> service.getContactByUserId(99L));
    }

    @Test
    void searchWrapsTheTermInAWildcardAndSortsByIdAscending() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);
        when(repository.search(any(String.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search("  Tunis ", 0, 20);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(pattern.capture(), pageable.capture());
        assertThat(pattern.getValue()).isEqualTo("%tunis%");
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isAscending()).isTrue();
    }

    @Test
    void blankSearchMatchesEveryRow() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);
        when(repository.search(any(String.class), any(Pageable.class))).thenReturn(Page.empty());

        service.search("   ", 0, 20);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(repository).search(pattern.capture(), any(Pageable.class));
        assertThat(pattern.getValue()).isEmpty();
    }

    @Test
    void rejectsAnUnusablePageRequest() {
        authenticate(2L, "operations@fleetflow.local", FleetRole.OPERATIONS);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.search(null, -1, 20))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> service.search(null, 0, 0))
                .extracting(BusinessException::getErrorCode)
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private void authenticate(Long userId, String email, FleetRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtPrincipal(userId, email, role),
                null,
                List.of(new SimpleGrantedAuthority(role.authority()))));
    }

    private static Customer customer(Long id, Long userId, String firstName, String lastName) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setUserId(userId);
        customer.setFirstName(firstName);
        customer.setLastName(lastName);
        customer.setEmail("customer" + userId + "@fleetflow.local");
        customer.setPhone("+21620100101");
        return customer;
    }
}
