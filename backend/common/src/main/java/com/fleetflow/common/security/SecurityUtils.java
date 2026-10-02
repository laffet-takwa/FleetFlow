package com.fleetflow.common.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fleetflow.common.exception.BusinessException;
import com.fleetflow.common.exception.ErrorCode;

/** Convenience accessors for the authenticated caller. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<JwtPrincipal> currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof JwtPrincipal jwtPrincipal ? Optional.of(jwtPrincipal) : Optional.empty();
    }

    /** @throws BusinessException when the request is anonymous. */
    public static JwtPrincipal requirePrincipal() {
        return currentPrincipal().orElseThrow(() ->
                new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication is required"));
    }

    public static Long currentUserId() {
        return currentPrincipal().map(JwtPrincipal::userId).orElse(null);
    }

    public static Long requireUserId() {
        return requirePrincipal().userId();
    }

    public static String currentEmail() {
        return currentPrincipal().map(JwtPrincipal::email).orElse(null);
    }

    public static FleetRole currentRole() {
        return currentPrincipal().map(JwtPrincipal::role).orElse(null);
    }

    public static boolean hasRole(FleetRole role) {
        return currentPrincipal().map(p -> p.role() == role).orElse(false);
    }

    public static boolean hasAnyRole(FleetRole... roles) {
        FleetRole current = currentRole();
        if (current == null) {
            return false;
        }
        for (FleetRole role : roles) {
            if (role == current) {
                return true;
            }
        }
        return false;
    }

    public static boolean isStaff() {
        return hasAnyRole(FleetRole.ADMIN, FleetRole.OPERATIONS);
    }

    /**
     * Guards operations that are restricted to the owning user: a customer may only
     * read or mutate their own records, while staff may act on anybody's.
     *
     * @throws BusinessException with {@link ErrorCode#FORBIDDEN} when the check fails
     */
    public static void requireSelfOrStaff(Long ownerId) {
        JwtPrincipal principal = requirePrincipal();
        if (principal.role() == FleetRole.ADMIN || principal.role() == FleetRole.OPERATIONS) {
            return;
        }
        if (!ownerId.equals(principal.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You may only access your own resources");
        }
    }
}