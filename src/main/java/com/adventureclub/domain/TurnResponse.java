package com.adventureclub.domain;

import java.util.UUID;

/**
 * What the server sends back.
 * storyText is null when blocked=true — the UI should show a gentle redirect message.
 */
public record TurnResponse(
        UUID sessionId,
        String storyText,
        boolean blocked
) {}