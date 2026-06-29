package com.adventureclub.repository;

import com.adventureclub.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    // Returns the full conversation history for a session, oldest message first.
    // Spring Data generates the SQL from the method name — no @Query needed.
    List<Message> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
