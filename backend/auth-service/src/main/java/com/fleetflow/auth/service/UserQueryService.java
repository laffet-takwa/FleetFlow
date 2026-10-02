package com.fleetflow.auth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fleetflow.auth.dto.UserResponse;
import com.fleetflow.auth.entity.User;
import com.fleetflow.auth.mapper.UserMapper;
import com.fleetflow.auth.repository.UserRepository;
import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.security.FleetRole;

import jakarta.persistence.criteria.Predicate;

/** Staff facing directory search over the user table. */
@Service
public class UserQueryService {

    private static final char LIKE_ESCAPE = '\\';

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserQueryService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(FleetRole role, String search, int page, int size) {
        Page<User> users = userRepository.findAll(specification(role, search),
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.from(users, users.getContent().stream().map(userMapper::toResponse).toList());
    }

    private static Specification<User> specification(FleetRole role, String search) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                predicates.add(builder.equal(root.get("role"), role));
            }
            String pattern = likePattern(search);
            if (pattern != null) {
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("email")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get("firstName")), pattern, LIKE_ESCAPE),
                        builder.like(builder.lower(root.get("lastName")), pattern, LIKE_ESCAPE)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * A user typing {@code %} must search for a percent sign, so wildcards coming from
     * the query string are escaped rather than interpreted.
     */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}