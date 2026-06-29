package com.adventureclub.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * What the client sends on every turn.
 * sessionId is null on the very first turn — the server creates the session.
 */
public record TurnRequest(
        UUID sessionId,
        @NotBlank String interests,
        @NotBlank String agentName,
        @NotBlank String childMessage
) {}