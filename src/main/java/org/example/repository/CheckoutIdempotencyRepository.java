package org.example.repository;

import org.example.entity.CheckoutIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CheckoutIdempotencyRepository extends JpaRepository<CheckoutIdempotency, Long> {
    // Called only after the customer's users row is locked (CartService.lockForCheckout). A plain read is
    // deliberate: FOR UPDATE on a missing (user_id, key) would take a gap lock that two different customers
    // can both hold, and their later INSERTs would then deadlock each other.
    @Query(value = """
            SELECT request_hash AS requestHash, response_json AS responseJson
            FROM checkout_idempotency WHERE user_id = :userId AND idempotency_key = :key
            """, nativeQuery = true)
    Optional<StoredCheckout> find(long userId, String key);

    // Executes immediately so a UNIQUE violation surfaces inside the checkout transaction and rolls it back.
    @Modifying
    @Query(value = """
            INSERT INTO checkout_idempotency (user_id, idempotency_key, request_hash, order_id, response_json)
            VALUES (:userId, :key, :requestHash, :orderId, :responseJson)
            """, nativeQuery = true)
    int insert(long userId, String key, String requestHash, long orderId, String responseJson);

    interface StoredCheckout {
        String getRequestHash();
        String getResponseJson();
    }
}
