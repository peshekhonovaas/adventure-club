package com.adventureclub.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Data
@NoArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String childName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String interests;    // raw string: "dragons, pokemon" — no JSON needed in v1

    @Column(nullable = false)
    private String agentName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Session(String childName, String interests, String agentName) {
        this.childName = childName;
        this.interests = interests;
        this.agentName = agentName;
    }
}