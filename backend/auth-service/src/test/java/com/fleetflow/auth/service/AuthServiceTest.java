package com.fleetflow.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fleetflow.auth.client.CustomerProfileClient;
import com.fleetflow.auth.dto.AuthResponse;
import com.fleetflow.auth.dto.LoginRequest;
import com.fleetflow.auth.dto.RegisterRequest;
import com.fleetflow.auth.entity.User;
import com.fleetflow.auth.mapper.UserMapper;
import com.fleetflow.auth.repository.UserRepository;
import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;
import com.fleetflow.common.security.FleetRole;
import com.fleetflow.common.security.JwtProperties;
import com.fleetflow.common.security.JwtService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String ISSUER = "fleetflow-unit-test";

    private static final long ACCESS_TOKEN_TTL_SECONDS = 3600L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomerProfileClient customerProfileClient;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("unit-test-signing-secret-key-0123456789-abcdef");
        jwtProperties.setIssuer(ISSUER);
        jwtProperties.setAccessTokenTtl(Duration.ofSeconds(ACCESS_TOKEN_TTL_SECONDS));
        jwtProperties.setClockSkew(Duration.ZERO);

        jwtService = new JwtService(jwtProperties, new ObjectMapper());
        authService = new AuthService(userRepository, passwordEncoder, jwtService, customerProfileClient,
                new UserMapper());
    }

    @Test
    void registerCreatesACustomerWithAHashedPassword() {
        when(userRepository.existsByEmail("sonia.trabelsi@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User persisted = invocation.getArgument(0);
            persisted.setId(18L);
            return persisted;
        });

        AuthResponse response = authService.register(new RegisterRequest(" Sonia ", "Trabelsi",
                " Sonia.Trabelsi@Example.COM ", "+21620123456", "Password123!", " 12 rue des Palmiers "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User created = saved.getValue();

        assertThat(created.getRole()).isEqualTo(FleetRole.CUSTOMER);
        assertThat(created.getEmail()).isEqualTo("sonia.trabelsi@example.com");
        assertThat(created.getFirstName()).isEqualTo("Sonia");
        assertThat(created.getAddress()).isEqualTo("12 rue des Palmiers");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getPasswordHash())
                .isNotEqualTo("Password123!")
                .matches("^\\$2[aby]\\$.+");
        assertThat(passwordEncoder.matches("Password123!", created.getPasswordHash())).isTrue();

        assertThat(response.tokenType()).isEqualTo(AuthResponse.BEARER);
        assertThat(response.expiresIn()).isEqualTo(ACCESS_TOKEN_TTL_SECONDS);
        assertThat(response.user().id()).isEqualTo(18L);
        assertThat(response.user().role()).isEqualTo("CUSTOMER");

        assertThat(verifiedClaims(response).userId()).isEqualTo(18L);
        assertThat(verifiedClaims(response).role()).isEqualTo(FleetRole.CUSTOMER);
        assertThat(verifiedClaims(response).email()).isEqualTo("sonia.trabelsi@example.com");
    }

    @Test
    void registerForwardsTheAccountToCustomerServiceWithTheNewToken() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User persisted = invocation.getArgument(0);
            persisted.setId(18L);
            return persisted;
        });

        AuthResponse response = authService.register(registerRequest());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(customerProfileClient).preCreateProfile(any(User.class), token.capture());
        assertThat(token.getValue()).isEqualTo(response.accessToken());
    }

    @Test
    void registerRejectsAnEmailThatIsAlreadyRegistered() {
        when(userRepository.existsByEmail("sonia.trabelsi@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(customerProfileClient);
    }

    @Test
    void registerTurnsALostUniquenessRaceIntoTheSameConflict() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uq_users_email"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        verifyNoInteractions(customerProfileClient);
    }

    @Test
    void registerRequestRejectsAPasswordWithoutADigit() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            Set<ConstraintViolation<RegisterRequest>> weak = validator.validate(
                    new RegisterRequest("Sonia", "Trabelsi", "sonia.trabelsi@example.com", "+21620123456",
                            "abcdefgh", "Tunis"));

            assertThat(weak).isNotEmpty();
            assertThat(weak).extracting(violation -> violation.getPropertyPath().toString()).contains("password");
            assertThat(validator.validate(registerRequest())).isEmpty();
        }
    }

    @Test
    void registerRequestRejectsAPhoneThatIsNotANumber() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            RegisterRequest request = new RegisterRequest("Sonia", "Trabelsi", "sonia.trabelsi@example.com",
                    "+216 20 123 456", "Password123!", "Tunis");

            Set<ConstraintViolation<RegisterRequest>> violations = factory.getValidator().validate(request);

            assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("phone");
        }
    }

    @Test
    void loginReturnsATokenForTheMatchingCredentials() {
        when(userRepository.findByEmailIgnoreCase("customer1@fleetflow.local"))
                .thenReturn(Optional.of(customer()));

        AuthResponse response = authService.login(new LoginRequest("Customer1@FleetFlow.local", "Password123!"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(ACCESS_TOKEN_TTL_SECONDS);
        assertThat(response.user().id()).isEqualTo(8L);
        assertThat(response.user().role()).isEqualTo("CUSTOMER");
        assertThat(verifiedClaims(response).userId()).isEqualTo(8L);
        assertThat(verifiedClaims(response).role()).isEqualTo(FleetRole.CUSTOMER);
    }

    @Test
    void loginRejectsAWrongPassword() {
        when(userRepository.findByEmailIgnoreCase("customer1@fleetflow.local"))
                .thenReturn(Optional.of(customer()));

        assertThatThrownBy(() -> authService.login(new LoginRequest("customer1@fleetflow.local", "WrongPassword1")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void loginRejectsAnUnknownEmailWithTheSameError() {
        when(userRepository.findByEmailIgnoreCase("ghost@fleetflow.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@fleetflow.local", "Password123!")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password")
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void loginRejectsADisabledAccount() {
        User disabled = customer();
        disabled.setEnabled(false);
        when(userRepository.findByEmailIgnoreCase("customer1@fleetflow.local")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> authService.login(new LoginRequest("customer1@fleetflow.local", "Password123!")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password")
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    private JwtService.Claims verifiedClaims(AuthResponse response) {
        return jwtService.parse(response.accessToken())
                .orElseThrow(() -> new AssertionError("Token issued by the service must verify"));
    }

    private User customer() {
        User user = new User();
        user.setId(8L);
        user.setFirstName("Ahmed");
        user.setLastName("Ben Ali");
        user.setEmail("customer1@fleetflow.local");
        user.setPhone("+21620100208");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(FleetRole.CUSTOMER);
        user.setEnabled(true);
        return user;
    }

    private static RegisterRequest registerRequest() {
        return new RegisterRequest("Sonia", "Trabelsi", "sonia.trabelsi@example.com", "+21620123456",
                "Password123!", "12 rue des Palmiers");
    }
}