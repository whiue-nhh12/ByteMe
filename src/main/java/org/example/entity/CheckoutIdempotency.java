package org.example.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

// Rows are inserted with a native statement (CheckoutIdempotencyRepository.insert) inside the checkout
// transaction; the entity only gives Spring Data a domain type and documents the mapping.
@Entity
@Table(name = "checkout_idempotency")
@Getter
@NoArgsConstructor
public class CheckoutIdempotency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "response_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
