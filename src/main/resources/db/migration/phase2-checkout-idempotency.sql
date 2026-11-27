-- Phase 2 migration: add checkout_idempotency to an existing ByteMe database.
-- schema.sql only creates missing tables on a fresh volume (ddl-auto: none), so run this once on
-- databases created before phase 2. Safe to re-run (CREATE TABLE IF NOT EXISTS); does not touch data.
-- docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
--   < src/main/resources/db/migration/phase2-checkout-idempotency.sql
SET NAMES utf8mb4 COLLATE utf8mb4_0900_as_ci;
SET time_zone = '+00:00';

-- Phase 2: one successful checkout per (customer, Idempotency-Key). Rows are written in the
-- checkout transaction, so a failed checkout leaves no key behind and the same key can be retried.
CREATE TABLE IF NOT EXISTS `checkout_idempotency` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `request_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA-256 of the normalized CreateOrderRequest',
    `order_id` BIGINT NOT NULL,
    `response_json` MEDIUMTEXT NOT NULL COMMENT 'OrderDto returned by the first successful checkout; replayed verbatim',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_checkout_idempotency_user_key` UNIQUE (`user_id`, `idempotency_key`),
    CONSTRAINT `uq_checkout_idempotency_order` UNIQUE (`order_id`),
    CONSTRAINT `ck_checkout_idempotency_key` CHECK (`idempotency_key` REGEXP '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT `ck_checkout_idempotency_hash` CHECK (`request_hash` REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT `fk_checkout_idempotency_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_checkout_idempotency_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_ci;
