package com.github.prreviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for processing GitHub webhook events
 */
@Service
public class WebhookService {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);
    
    private final ObjectMapper objectMapper;
    private final PrReviewService prReviewService;
    
    @Autowired
    public WebhookService(ObjectMapper objectMapper, PrReviewService prReviewService) {
        this.objectMapper = objectMapper;
        this.prReviewService = prReviewService;
    }
    
    public void processWebhook(String event, String payload) throws Exception {
        JsonNode payloadNode = objectMapper.readTree(payload);
        
        switch (event) {
            case "pull_request":
                handlePullRequestEvent(payloadNode);
                break;
            case "pull_request_review_comment":
                handleReviewCommentEvent(payloadNode);
                break;
            case "ping":
                logger.info("GitHub webhook ping received");
                break;
            default:
                logger.info("Unhandled event type: {}", event);
        }
    }
    
    private void handlePullRequestEvent(JsonNode payload) {
        String action = payload.get("action").asText();
        
        // Only process opened PRs and new commits
        if (!"opened".equals(action) && !"synchronize".equals(action)) {
            logger.info("Skipping PR action: {}", action);
            return;
        }
        
        JsonNode pullRequest = payload.get("pull_request");
        long installationId = payload.get("installation").get("id").asLong();
        
        logger.info("Processing PR {}: {}", action, pullRequest.get("html_url").asText());
        
        try {
            prReviewService.reviewPullRequest(pullRequest, installationId);
        } catch (Exception e) {
            logger.error("Error reviewing PR", e);
        }
    }
    
    private void handleReviewCommentEvent(JsonNode payload) {
        String action = payload.get("action").asText();
        
        // Only process new comments
        if (!"created".equals(action)) {
            return;
        }
        
        JsonNode comment = payload.get("comment");
        JsonNode pullRequest = payload.get("pull_request");
        long installationId = payload.get("installation").get("id").asLong();
        
        // Check if the comment is a reply to our bot
        String botName = "pr-review-bot"; // This should come from config
        String commentBody = comment.get("body").asText().toLowerCase();
        
        if (comment.get("in_reply_to_id") != null && 
            commentBody.contains("@" + botName.toLowerCase())) {
            
            logger.info("Processing reply to bot comment: {}", comment.get("html_url").asText());
            
            try {
                prReviewService.handleCommentReply(comment, pullRequest, installationId);
            } catch (Exception e) {
                logger.error("Error handling comment reply", e);
            }
        }
    }
}
