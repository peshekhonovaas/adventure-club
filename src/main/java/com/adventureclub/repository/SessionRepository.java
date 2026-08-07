package com.adventureclub.repository;

import com.adventureclub.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    // The hero's most recent adventure — used to resume it after they log in again.
    Optional<Session> findFirstByUsernameOrderByCreatedAtDesc(String username);
}

