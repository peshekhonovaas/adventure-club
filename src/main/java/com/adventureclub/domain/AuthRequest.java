package com.adventureclub.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials for register/login: the hero name and the secret word.
 * The same shape serves both endpoints.
 */
public record AuthRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 100) String password
) {}
