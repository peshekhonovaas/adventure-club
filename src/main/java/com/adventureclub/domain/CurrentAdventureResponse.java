package com.adventureclub.domain;

import java.util.UUID;

/**
 * The hero's last in-progress adventure, returned by {@code GET /session/current}
 * so the frontend can resume it after the hero logs in again (even on another
 * device/browser).
 * <p>
 * {@code storyText} is the latest Game Master beat (the last assistant message),
 * and {@code imageUrl} is the current living picture as a browser-ready
 * {@code data:} URL (or null when none exists yet).
 */
public record CurrentAdventureResponse(
        UUID sessionId,
        String interests,
        String agentName,
        String storyText,
        String imageUrl
) {}
