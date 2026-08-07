package com.adventureclub.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A "hero" account for the Adventure Club. The {@code password} column stores a
 * BCrypt hash of the child's secret word — never the plain text. Usernames are
 * unique case-insensitively (enforced by {@code ux_users_username_lower}).
 */
@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;    // BCrypt hash — never the plain secret word

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public User(String username, String password) {
        this.username = username;
        this.password = password;
    }
}
