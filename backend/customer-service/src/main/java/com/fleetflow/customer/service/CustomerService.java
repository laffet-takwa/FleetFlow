package com.fleetflow.customer.service;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.JwtPrincipal;
import com.fleetflow.common.security.SecurityUtils;

import com.fleetflow.customer.dto.CustomerProfilePreCreateRequest;
import com.fleetflow.customer.dto.UpdateProfileRequest;
import com.fleetflow.customer.entity.Customer;
import com.fleetflow.customer.repository.CustomerRepository;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private static final int MAX_PAGE_SIZE = 200;

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the profile of the caller, creating a skeleton on first use.
     *
     * <p>Registration and customer provisioning live in different services, so a user
     * can hold a valid token before their profile row exists. Provisioning here rather
     * than in an event listener means {@code /me} never fails for a legitimate caller.
     *
     * <p>Deliberately not {@code @Transactional}. {@code customers.user_id} is unique,
     * so a second concurrent {@code /me} for the same user loses the insert. If that
     * insert shared a transaction with the read that preceded it, the constraint
     * violation would mark the transaction rollback-only and the loser could not read
     * the row the winner had just committed — it would surface as a 500. Giving the
     * insert its own transaction lets the loser recover and return the same profile.
     */
    public Customer getOrCreateCurrent() {
        JwtPrincipal principal = SecurityUtils.requirePrincipal();
        Optional<Customer> existing = repository.findByUserId(principal.userId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return provisionSkeleton(principal);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent request for the same user; the unique
            // index arbitrated and the other transaction already committed.
            log.info("Profile for userId={} was provisioned concurrently, reusing it [correlationId={}]",
                    principal.userId(), CorrelationId.getOrCreate());
            return repository.findByUserId(principal.userId())
                    .orElseThrow(() -> ResourceNotFoundException.of("Customer for user", principal.userId()));
        }
    }

    /**
     * Creates the profile from the details captured at registration.
     *
     * <p>Called by the auth service immediately after an account is created, so that the
     * name, phone and address supplied during registration are not lost. Lazy
     * provisioning on {@code /api/customers/me} cannot do this: by the time a browser
     * first calls that endpoint the registration payload is gone, and a skeleton with
     * empty names is what the driver would see at the door.
     *
     * <p>Idempotent by {@code userId}: a repeat call updates the existing profile rather
     * than creating a second one, so a retried registration cannot duplicate it. Not
     * {@code @Transactional} for the same reason as {@link #getOrCreateCurrent()}: a
     * repeat that races a lazy provision must be able to read the row the other
     * transaction committed rather than fail on the unique index.
     */
    public Customer preCreate(CustomerProfilePreCreateRequest request) {
        Customer customer = repository.findByUserId(request.userId())
                .orElseGet(() -> {
                    Customer created = new Customer();
                    created.setUserId(request.userId());
                    return created;
                });

        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setAddress(request.address());

        try {
            Customer saved = repository.saveAndFlush(customer);
            log.info("Pre-created customer profile {} for userId={} [correlationId={}]",
                    saved.getId(), saved.getUserId(), CorrelationId.getOrCreate());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // Concurrent lazy provisioning won the unique index; apply the registration
            // details to that row instead of failing a registration that already succeeded.
            Customer existing = repository.findByUserId(request.userId())
                    .orElseThrow(() -> ResourceNotFoundException.of("Customer for user", request.userId()));
            existing.setFirstName(request.firstName());
            existing.setLastName(request.lastName());
            existing.setEmail(request.email());
            existing.setPhone(request.phone());
            existing.setAddress(request.address());
            Customer saved = repository.saveAndFlush(existing);
            log.info("Merged pre-created customer profile {} into the concurrently "
                    + "provisioned row for userId={} [correlationId={}]",
                    saved.getId(), request.userId(), CorrelationId.getOrCreate());
            return saved;
        }
    }

    private Customer provisionSkeleton(JwtPrincipal principal) {
        Customer customer = new Customer();
        customer.setUserId(principal.userId());
        customer.setEmail(principal.email() == null ? "" : principal.email());
        customer.setFirstName("");
        customer.setLastName("");
        customer.setPhone("");
        // saveAndFlush, not save: the timestamp callbacks run on flush, and the caller
        // maps this entity to a response as soon as it is returned.
        Customer saved = repository.saveAndFlush(customer);
        log.info("Provisioned customer profile {} for userId={} [correlationId={}]",
                saved.getId(), principal.userId(), CorrelationId.getOrCreate());
        return saved;
    }

    @Transactional
    public Customer updateCurrentProfile(UpdateProfileRequest request) {
        Customer customer = getOrCreateCurrent();
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setPhone(request.phone());
        customer.setAddress(request.address());
        customer.setCity(request.city());
        customer.setPostalCode(request.postalCode());
        return repository.saveAndFlush(customer);
    }

    @Transactional(readOnly = true)
    public Customer getById(Long id) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", id));
        SecurityUtils.requireSelfOrStaff(customer.getUserId());
        return customer;
    }

    @Transactional(readOnly = true)
    public Page<Customer> search(String search, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "page must be zero or greater and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String pattern = normalise(search);
        return repository.search(pattern, PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    /** For service-to-service calls that hold an auth user id rather than a customer row id. */
    @Transactional(readOnly = true)
    public Customer getContactByUserId(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Customer for user", userId));
    }

    private static String normalise(String search) {
        if (search == null || search.isBlank()) {
            return "";
        }
        return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
