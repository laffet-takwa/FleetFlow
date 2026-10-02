package com.fleetflow.customer.service;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     */
    @Transactional
    public Customer getOrCreateCurrent() {
        JwtPrincipal principal = SecurityUtils.requirePrincipal();
        return repository.findByUserId(principal.userId())
                .orElseGet(() -> provisionSkeleton(principal));
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
