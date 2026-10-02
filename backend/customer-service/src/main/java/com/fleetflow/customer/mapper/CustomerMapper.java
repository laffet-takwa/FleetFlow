package com.fleetflow.customer.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.customer.dto.CustomerContactResponse;
import com.fleetflow.customer.dto.CustomerResponse;
import com.fleetflow.customer.entity.Customer;

@Component
public class CustomerMapper {

    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getUserId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getCity(),
                customer.getPostalCode(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }

    public CustomerContactResponse toContactResponse(Customer customer) {
        return new CustomerContactResponse(
                customer.getUserId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getAddress(),
                customer.getCity(),
                customer.getPostalCode());
    }
}
