package com.fleetflow.auth.service;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.auth.client.CustomerProfileClient;
import com.fleetflow.auth.dto.AuthResponse;
import com.fleetflow.auth.dto.LoginRequest;
import com.fleetflow.auth.dto.RegisterRequest;
import com.fleetflow.auth.dto.UserResponse;
import com.fleetflow.auth.entity.User;
import com.fleetflow.auth.mapper.UserMapper;
import com.fleetflow.auth.repository.UserRepository;
import com.fleetflow.common.correlation.CorrelationId;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.exception.ResourceNotFoundException;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtService;

/**
 * Registration and login. Every failure a caller can provoke is reported with the
 * same error code so the endpoints cannot be used to enumerate accounts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CustomerProfileClient customerProfileClient;
    private final UserMapper userMapper;

    /**
     * Compared against when the account does not exist so that a missing account costs
     * the same wall clock time as a wrong password.
     */
    private final String absentAccountHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            CustomerProfileClient customerProfileClient, UserMapper userMapper) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.customerProfileClient = customerProfileClient;
        this.userMapper = userMapper;
        this.absentAccountHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Not transactional on purpose: the profile hand-off below must run once the
     * account is committed, and {@code save} already runs in its own transaction.
     */
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }

        User user = new User();
        user.setFirstName(trim(request.firstName()));
        user.setLastName(trim(request.lastName()));
        user.setEmail(email);
        user.setPhone(trim(request.phone()));
        user.setAddress(trim(request.address()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(FleetRole.CUSTOMER);
        user.setEnabled(true);

        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent registration: the unique index arbitrates.
            throw new BusinessException(ErrorCode.CONFLICT,
                    "An account with this email already exists", ex);
        }

        log.info("Registered user {} as {} [correlationId={}]", saved.getId(), saved.getRole(),
                CorrelationId.getOrCreate());

        AuthResponse response = issueToken(saved);
        customerProfileClient.preCreateProfile(saved, response.accessToken());
        return response;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        // Always compared, never short-circuited: an unknown email must cost the same as a wrong password.
        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user != null ? user.getPasswordHash() : absentAccountHash);

        if (user == null || !passwordMatches || !user.isEnabled()) {
            if (user != null && !user.isEnabled()) {
                log.warn("Rejected login for disabled account {} [correlationId={}]", user.getId(),
                        CorrelationId.getOrCreate());
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_MESSAGE);
        }

        log.info("User {} authenticated [correlationId={}]", user.getId(), CorrelationId.getOrCreate());
        return issueToken(user);
    }

    @Transactional(readOnly = true)
    public UserResponse loadCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        return userMapper.toResponse(user);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(token, AuthResponse.BEARER, jwtService.getExpiresInSeconds(),
                userMapper.toResponse(user));
    }

    private static BusinessException duplicateEmail() {
        return new BusinessException(ErrorCode.CONFLICT, "An account with this email already exists");
    }

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}