package com.adventureclub.repository;

import com.adventureclub.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    // Case-insensitive lookup so "Grogu" and "grogu" resolve to the same hero,
    // matching the case-insensitive uniqueness enforced in the schema.
    Optional<User> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);
}
