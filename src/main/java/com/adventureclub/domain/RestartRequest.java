package com.adventureclub.domain;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * What the client sends to restart an adventure.
 * The session must already exist — restarting wipes its story history and picture(s)
 * while keeping the session (and its interests) so a fresh adventure can begin.
 */
public record RestartRequest(
        @NotNull UUID sessionId
) {}
