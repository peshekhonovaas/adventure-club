package com.adventureclub.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for changing the signed-in hero's secret word: the current secret word
 * (re-verified server-side) and the new one to store (BCrypt-hashed).
 */
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(max = 100) String newPassword
) {}
