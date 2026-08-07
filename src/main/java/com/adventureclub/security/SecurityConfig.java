package com.adventureclub.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Real backend auth wiring.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Stateful HTTP session (JSESSIONID cookie) keeps a hero signed in across
 *       requests — the SPA is served from the same origin, so the cookie rides
 *       along automatically.</li>
 *   <li>CSRF is disabled: this is a JSON API consumed by a same-origin SPA, and
 *       there are no cookie-authenticated form posts from other origins to protect.</li>
 *   <li>Only {@code /session/**} (the game) requires authentication; the static
 *       SPA, the dev console and the {@code /auth/**} endpoints stay public so the
 *       login page itself can load and submit.</li>
 *   <li>Unauthenticated API calls get a plain 401 (no login-form redirect), which
 *       the frontend turns back into "please sign in".</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/session/**").authenticated()
                        .anyRequest().permitAll()
                )
                // Stateful sessions so login persists; created on demand at login.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // API-style: return 401 instead of redirecting to a login page.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout.disable()); // logout handled by AuthController

        return http.build();
    }
}
