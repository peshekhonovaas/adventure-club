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

    // The "hero" (authenticated username) who owns this adventure. Nullable for
    // legacy/anonymous sessions created before auth existed. Lets us reload the
    // hero's last adventure after they log in again (even on another device).
    @Column
    private String username;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String interests;    // raw string: "dragons, pokemon" — no JSON needed in v1

    @Column(nullable = false)
    private String agentName;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // The latest picture the Illustrator generated for this adventure — kept server-side
    // so the next turn can paint the new beat onto it (the living canvas). Raw base64
    // (no {@code data:} prefix) plus its MIME type. Both null until the first picture exists.
    @Column(columnDefinition = "TEXT")
    private String sceneImageData;

    @Column
    private String sceneImageMediaType;

    // The picture that was the living canvas BEFORE the latest generated one — kept so the
    // child can undo the last generated picture and go back to the previous one. Raw base64
    // (no {@code data:} prefix) plus its MIME type. Both null until at least two pictures exist.
    @Column(columnDefinition = "TEXT")
    private String previousSceneImageData;

    @Column
    private String previousSceneImageMediaType;

    public Session(String childName, String interests, String agentName) {
        this.childName = childName;
        this.interests = interests;
        this.agentName = agentName;
    }
}