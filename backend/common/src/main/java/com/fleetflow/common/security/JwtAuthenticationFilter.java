package com.fleetflow.common.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Populates the {@code SecurityContext} from a {@code Bearer} access token.
 *
 * <p>A missing or unusable token does not fail the request here: the call is left
 * anonymous and the service's authorization rules decide whether the endpoint
 * tolerates that, which keeps public endpoints reachable.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(BEARER_PREFIX.length()).trim();
            jwtService.parse(token)
                    .filter(claims -> !claims.isExpired(jwtService.getClockSkew()))
                    .ifPresent(claims -> {
                        JwtPrincipal principal =
                                new JwtPrincipal(claims.userId(), claims.email(), claims.role());
                        // parse() only yields a token whose role is in the platform
                        // vocabulary, so the authority list is never empty here.
                        var authentication = new UsernamePasswordAuthenticationToken(principal, token,
                                List.of(new SimpleGrantedAuthority(claims.role().authority())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        chain.doFilter(request, response);
    }
}