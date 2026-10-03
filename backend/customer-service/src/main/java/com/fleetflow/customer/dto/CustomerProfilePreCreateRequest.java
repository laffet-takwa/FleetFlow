package com.fleetflow.customer.dto;

/**
 * Details captured by the auth service at registration, used to pre-create the customer
 * profile so the name, phone and address supplied there are not discarded.
 *
 * @param userId    auth-service user id, the profile's identity key
 * @param firstName required
 * @param lastName  required
 * @param email     lower-cased by the auth service
 * @param phone     required
 * @param address   optional, may be null
 */
public record CustomerProfilePreCreateRequest(
        Long userId,
        String firstName,
        String lastName,
        String email,
        String phone,
        String address) {
}