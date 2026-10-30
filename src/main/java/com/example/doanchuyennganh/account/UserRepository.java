package com.example.doanchuyennganh.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    default Optional<User> findByEmail(String email) {
        return findByNormalizedEmail(EmailNormalizer.normalize(email));
    }

    @Query("select u from User u where u.email = :email")
    Optional<User> findByNormalizedEmail(@Param("email") String normalizedEmail);
}
