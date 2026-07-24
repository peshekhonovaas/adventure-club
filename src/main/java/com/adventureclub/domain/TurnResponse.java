package com.adventureclub.domain;

import java.util.UUID;

/**
 * What the server sends back.
 * storyText is null when blocked=true — the UI should show a gentle redirect message.
 * <p>
 * imageUrl is an optional browser-ready illustration of this story beat: a
 * {@code data:image/png;base64,...} URL from the Illustrator (or a plain http(s)
 * URL if a future provider hosts images). It is null when image generation is
 * disabled, unavailable, or failed, and always null when blocked=true — the UI
 * simply shows no new picture in that case.
 */
public record TurnResponse(
        UUID sessionId,
        String storyText,
        String imageUrl,
        boolean blocked
) {}