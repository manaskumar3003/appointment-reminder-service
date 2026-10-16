package com.product.appointment.controller;

import com.product.appointment.dto.DeliveryReceiptRequest;
import com.product.appointment.service.DeliveryFeedbackService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Late delivery receipts from the provider. Signed, so nobody else can mark a reminder delivered;
 * idempotent, because providers retry receipts and send them out of order. An unknown message is
 * acknowledged with 200 so the provider stops retrying it.
 */
@RestController
@RequestMapping("/webhooks")
public class DeliveryWebhookController {

    private final DeliveryFeedbackService deliveryFeedbackService;
    private final byte[] secret;

    public DeliveryWebhookController(DeliveryFeedbackService deliveryFeedbackService,
                                     @Value("${reminders.webhook-secret:dev-webhook-secret}") String secret) {
        this.deliveryFeedbackService = deliveryFeedbackService;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/delivery-status")
    public ResponseEntity<Map<String, Object>> receipt(
            @RequestHeader(name = "X-Signature", required = false) String signature,
            @Valid @RequestBody DeliveryReceiptRequest receipt) {

        if (signature == null || !MessageDigest.isEqual(
                hmac(receipt.signedPayload()).getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad signature"));
        }
        if (receipt.idempotencyKey() == null && receipt.providerMessageId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "idempotencyKey or providerMessageId required"));
        }

        boolean applied = deliveryFeedbackService.applyReceipt(receipt.idempotencyKey(), receipt.providerMessageId(),
                receipt.status() == DeliveryReceiptRequest.Status.DELIVERED, receipt.error());
        return ResponseEntity.ok(Map.of("applied", applied));
    }

    /** Hex HMAC-SHA256 of the payload with the shared secret - what the provider puts in X-Signature. */
    String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
