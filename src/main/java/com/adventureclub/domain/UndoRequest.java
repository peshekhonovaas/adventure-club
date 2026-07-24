package com.adventureclub.domain;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * What the client sends to undo the last generated picture.
 * The session must already exist (a picture can only be undone within a session).
 */
public record UndoRequest(
        @NotNull UUID sessionId
) {}
