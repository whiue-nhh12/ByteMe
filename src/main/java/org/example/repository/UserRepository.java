package org.example.repository;

import java.util.Optional;
import org.example.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Query(value = "SELECT id FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(long id);

    boolean existsByEmailOrPhone(String email, String phone);
    Optional<User> findByEmail(String email);
}
