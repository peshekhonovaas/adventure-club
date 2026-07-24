package com.adventureclub.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * What the client sends on every turn.
 * sessionId is null on the very first turn — the server creates the session.
 * <p>
 * imageData / imageMediaType are optional: when the child uploads a picture,
 * the client sends the raw base64 (no {@code data:} prefix) plus its MIME type
 * (e.g. "image/png"). Both are null on a normal text-only turn.
 * <p>
 * The current living picture of the adventure (the Illustrator's previous output)
 * is no longer sent by the client — the server keeps it on the {@link Session} and
 * merges a fresh upload INTO it, so the living picture keeps growing.
 */
public record TurnRequest(
        UUID sessionId,
        @NotBlank String interests,
        @NotBlank String agentName,
        @NotBlank String childMessage,
        String imageData,
        String imageMediaType
) {
    /** True when this turn carries a picture the Game Master should look at. */
    public boolean hasImage() {
        return imageData != null && !imageData.isBlank()
                && imageMediaType != null && !imageMediaType.isBlank();
    }
}