package com.github.prreviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main service for PR review functionality
 */
@Service
public class PrReviewService {
    
    private static final Logger logger = LoggerFactory.getLogger(PrReviewService.class);
    
    private final GitHubService githubService;
    private final LlmService llmService;
    
    @Autowired
    public PrReviewService(GitHubService githubService, LlmService llmService) {
        this.githubService = githubService;
        this.llmService = llmService;
    }
    
    /**
     * Reviews a pull request asynchronously
     */
    @Async
    public CompletableFuture<Void> reviewPullRequest(JsonNode pullRequest, long installationId) {
        try {
            logger.info("Starting PR review for: {}", pullRequest.get("html_url").asText());
            
            // Extract repository information
            GitHubService.RepositoryInfo repoInfo = githubService.extractRepositoryInfo(pullRequest);
            
            // Create GitHub client
            GitHub github = githubService.createGitHubClient(installationId);
            
            // Get PR diff
            String diffContent = githubService.getPrDiff(github, repoInfo.getOwner(), 
                                                repoInfo.getRepo(), repoInfo.getPrNumber());
            
            // Analyze with LLM
            String pullRequestTitle = pullRequest.get("title").asText();
            String pullRequestDescription = pullRequest.get("body").asText();
            
            List<LlmService.ReviewSuggestion> suggestions = llmService
                    .analyzeCodeChanges(diffContent , pullRequestTitle, prDescription)
                    .block(); 
            
            // Post review comments
            for (LlmService.ReviewSuggestion suggestion : suggestions) {
                postReviewComment(github, repoInfo, suggestion);
            }
            
            logger.info("Completed PR review with {} suggestions", suggestions.size());
            
        } catch (Exception e) {
            logger.error("Error during PR review", e);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Handles replies to bot comments
     */
    @Async
    public CompletableFuture<Void> handleCommentReply(JsonNode comment, JsonNode pullRequest, long installationId) {
        try {
            logger.info("Handling comment reply: {}", comment.get("html_url").asText());
            
            // Extract repository information
            GitHubService.RepositoryInfo repoInfo = githubService.extractRepositoryInfo(pullRequest);
            
            // Create GitHub client
            GitHub github = githubService.createGitHubClient(installationId);
            
            // Get the original comment context
            long originalCommentId = comment.get("in_reply_to_id").asLong();
            String originalCommentBody = comment.get("body").asText();
            
            // Extract the question from the comment
            String question = extractQuestionFromComment(originalCommentBody);
            
            // Generate response using LLM
            String response = llmService.generateResponseToQuestion(
                    question, 
                    "Original comment context", // This should be fetched from the original comment
                    "Code context" // This should be fetched from the PR diff
            ).block();
            
            // Post the response
            githubService.replyToReviewComment(github, originalCommentId, response);
            
            logger.info("Posted response to comment reply");
            
        } catch (Exception e) {
            logger.error("Error handling comment reply", e);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Posts a review comment for a suggestion
     */
    private void postReviewComment(GitHub github, GitHubService.RepositoryInfo repoInfo, 
                                 LlmService.ReviewSuggestion suggestion) {
        try {
            String commentBody = buildCommentBody(suggestion);
            
            githubService.postReviewComment(
                    github,
                    repoInfo.getOwner(),
                    repoInfo.getRepo(),
                    repoInfo.getPrNumber(),
                    commentBody,
                    suggestion.getFile(),
                    suggestion.getLine(),
                    suggestion.getSide()
            );
            
        } catch (Exception e) {
            logger.error("Error posting review comment", e);
        }
    }
    
    /**
     * Builds the comment body for a suggestion
     */
    private String buildCommentBody(LlmService.ReviewSuggestion suggestion) {
        StringBuilder body = new StringBuilder();
        
        // Add severity and category emoji
        String emoji = getEmojiForCategory(suggestion.getCategory());
        body.append(emoji).append(" **").append(suggestion.getCategory()).append("**");
        
        if ("ERROR".equals(suggestion.getSeverity())) {
            body.append(" ��");
        } else if ("WARNING".equals(suggestion.getSeverity())) {
            body.append(" ⚠️");
        }
        
        body.append("\n\n");
        body.append(suggestion.getMessage());
        
        if (suggestion.getSuggestion() != null && !suggestion.getSuggestion().isEmpty()) {
            body.append("\n\n**Suggestion:**\n");
            body.append("```\n");
            body.append(suggestion.getSuggestion());
            body.append("\n```");
        }
        
        return body.toString();
    }
    
    /**
     * Gets emoji for category
     */
    private String getEmojiForCategory(String category) {
        return switch (category.toUpperCase()) {
            case "BUG" -> "🐛";
            case "SECURITY" -> "🔒";
            case "PERFORMANCE" -> "⚡";
            case "STYLE" -> "🎨";
            case "MAINTAINABILITY" -> "🔧";
            default -> "💡";
        };
    }
    
    /**
     * Extracts question from comment body
     */
    private String extractQuestionFromComment(String commentBody) {
        // Simple extraction - look for text after @bot-name
        String botName = "pr-review-bot";
        int botIndex = commentBody.toLowerCase().indexOf("@" + botName.toLowerCase());
        
        if (botIndex != -1) {
            String afterBot = commentBody.substring(botIndex + botName.length() + 1).trim();
            return afterBot.isEmpty() ? "Can you explain this?" : afterBot;
        }
        
        return commentBody;
    }
}
