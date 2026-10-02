package com.fleetflow.auth.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fleetflow.auth.dto.UserResponse;
import com.fleetflow.auth.service.UserQueryService;
import com.fleetflow.common.api.ApiErrorResponse;
import com.fleetflow.common.api.PageResponse;
import com.fleetflow.common.security.FleetRole;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Tag(name = "Users", description = "Staff directory over the platform identities")
@RestController
@Validated
@RequestMapping("/api/auth/users")
public class UserController {

    private static final int MAX_PAGE_SIZE = 200;

    private final UserQueryService userQueryService;

    public UserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Operation(summary = "Search users",
            description = "Free text matches the email or the first or last name, case-insensitively. "
                    + "Results are ordered by ascending id, which keeps the seeded identity space stable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching page of users",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
            @ApiResponse(responseCode = "403", description = "Caller is neither ADMIN nor OPERATIONS",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public PageResponse<UserResponse> search(
            @Parameter(description = "Restrict to a single role", example = "DRIVER")
            @RequestParam(required = false) FleetRole role,
            @Parameter(description = "Matched against email, first name and last name", example = "trabelsi")
            @RequestParam(required = false) String search,
            @Parameter(description = "Zero based page index", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, capped at " + MAX_PAGE_SIZE, example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        return userQueryService.search(role, search, page, Math.min(size, MAX_PAGE_SIZE));
    }
}