package com.adventureclub.domain;

/**
 * What the auth endpoints return: the signed-in hero's display name.
 * The stateful HTTP session (JSESSIONID cookie) carries the actual credential,
 * so no token is sent in the body.
 */
public record AuthResponse(String username) {}
