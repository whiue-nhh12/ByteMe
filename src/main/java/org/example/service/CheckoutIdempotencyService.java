package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.common.BusinessException;
import org.example.dto.CreateOrderRequest;
import org.example.dto.OrderDto;
import org.example.repository.CheckoutIdempotencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

// Idempotency-Key for POST /api/orders (docs/phase2/checkout-design.md, "Hợp đồng idempotency checkout").
@Service
@RequiredArgsConstructor
public class CheckoutIdempotencyService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final CheckoutIdempotencyRepository records;
    private final ObjectMapper json;

    // Null means the client sent no header (phase 1 behaviour). Anything else must match the grammar exactly.
    public static String normalizeKey(String raw) {
        if (raw == null) return null;
        if (!KEY.matcher(raw).matches())
            throw BusinessException.invalid("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key phải dài 1–64 ký tự thuộc [A-Za-z0-9._-].");
        return raw;
    }

    // SHA-256 over the request after OrderService's trimming/order-type rules, in a fixed field order with
    // explicit nulls and length prefixes, so JSON property order or whitespace never changes the hash.
    public static String fingerprint(CreateOrderRequest input) {
        StringBuilder canonical = new StringBuilder("checkout-v1");
        field(canonical, "orderType", input.orderType() == null ? null : input.orderType().name());
        field(canonical, "tableNumber", input.tableNumber());
        field(canonical, "recipientName", input.recipientName());
        field(canonical, "shippingPhone", input.shippingPhone());
        field(canonical, "shippingAddress", input.shippingAddress());
        field(canonical, "paymentMethod", input.paymentMethod() == null ? null : input.paymentMethod().name());
        field(canonical, "note", input.note());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng", e);
        }
    }

    private static void field(StringBuilder out, String name, String value) {
        out.append('\n').append(name).append('=');
        if (value == null) out.append('-');
        else out.append(value.length()).append(':').append(value);
    }

    // Caller must already hold the customer's lock in the same transaction.
    // Same key + same payload: the stored response. Same key + other payload: 409, nothing changes.
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OrderDto> findReplay(long customerId, String key, String requestHash) {
        return records.find(customerId, key).map(stored -> {
            if (!stored.getRequestHash().equals(requestHash))
                throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho một yêu cầu đặt hàng khác.");
            return read(stored.getResponseJson());
        });
    }

    // Written in the checkout transaction: the key commits with the order or not at all.
    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(long customerId, String key, String requestHash, OrderDto order) {
        records.insert(customerId, key, requestHash, order.id(), write(order));
    }

    private String write(OrderDto order) {
        try {
            return json.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể lưu phản hồi checkout", e);
        }
    }

    private OrderDto read(String value) {
        try {
            return json.readValue(value, OrderDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không thể đọc phản hồi checkout đã lưu", e);
        }
    }
}
