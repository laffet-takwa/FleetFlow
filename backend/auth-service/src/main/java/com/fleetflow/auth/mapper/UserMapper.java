package com.fleetflow.auth.mapper;

import org.springframework.stereotype.Component;

import com.fleetflow.auth.dto.UserResponse;
import com.fleetflow.auth.entity.User;

@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }
        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole() == null ? null : user.getRole().name(),
                user.isEnabled());
    }
}