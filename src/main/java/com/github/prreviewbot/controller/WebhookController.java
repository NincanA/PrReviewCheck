package com.github.prreviewbot.controller;

import com.github.prreviewbot.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Controller for handling GitHub webhook events
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    
    private final WebhookService webhookService;
    private final String webhookSecret;
    
    public WebhookController(WebhookService webhookService, 
                           @Value("${github.app.webhook-secret}") String webhookSecret) {
        this.webhookService = webhookService;
        this.webhookSecret = webhookSecret;
    }
    
    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload) {
        
        logger.info("Received GitHub event: {}", event);
        
        try {
            // Validate signature
            if (!isValidSignature(payload, signature)) {
                logger.warn("Invalid webhook signature");
                return ResponseEntity.status(401).body("Unauthorized");
            }
            
            // Process the webhook
            webhookService.processWebhook(event, payload);
            
            return ResponseEntity.ok("Event processed successfully");
            
        } catch (Exception e) {
            logger.error("Error processing webhook", e);
            return ResponseEntity.status(500).body("Internal server error");
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "GitHub PR Review Bot",
            "timestamp", System.currentTimeMillis()
        ));
    }
    
    private boolean isValidSignature(String payload, String signature) {
        if (signature == null || webhookSecret == null) {
            return false;
        }
        
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + bytesToHex(hash);
            
            return MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), 
                                       expectedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.error("Error validating signature", e);
            return false;
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
