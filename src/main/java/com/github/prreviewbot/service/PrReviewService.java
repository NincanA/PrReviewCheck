package com.github.prreviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;

import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
            PagedIterable<GHPullRequestFileDetail> diffContentByFile = githubService.getPrDiff(github, repoInfo.getOwner(),
                                                repoInfo.getRepo(), repoInfo.getPrNumber());
            
            // Analyze with LLM
            String pullRequestTitle = pullRequest.get("title").asText();
            String pullRequestDescription = pullRequest.get("body").asText();
            
            List<LlmService.ReviewSuggestion> suggestions = llmService
                    .analyzeCodeChanges(diffContentByFile , pullRequestTitle, pullRequestDescription)
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

    private void postReviewComment(GitHub github,
                                   GitHubService.RepositoryInfo repoInfo,
                                   LlmService.ReviewSuggestion suggestion) {
        try {
            // Fetch commit SHA from Kohsuke client
            String commitSha = github.getRepository(repoInfo.getOwner() + "/" + repoInfo.getRepo())
                    .getPullRequest(repoInfo.getPrNumber())
                    .getHead()
                    .getSha();

            // Construct API URL
            String apiUrl = String.format(
                    "https://api.github.com/repos/%s/%s/pulls/%d/comments",
                    repoInfo.getOwner(), repoInfo.getRepo(), repoInfo.getPrNumber());

            // Build payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("body", buildCommentBody(suggestion));
            payload.put("commit_id", commitSha);
            payload.put("path", suggestion.getFile());
            payload.put("line", suggestion.getLine());
            payload.put("side", suggestion.getSide() != null ? suggestion.getSide() : "RIGHT");

            // Convert payload to JSON
            String json = new ObjectMapper().writeValueAsString(payload);

            // ❗Use the same installation token you used to build the GitHub client
            // Since Kohsuke GitHub hides it, keep it separately in your GitHubService
            // when you call createGitHubClient(installationId)
            String installationToken = githubService.getToken(); // implement getter in your service

            // Build HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + installationToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            // Send request
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            // Log success or error
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                logger.info("✅ Posted review comment on {} line {}",
                        suggestion.getFile(), suggestion.getLine());
            } else {
                logger.error("❌ Failed to post review comment: {} - {} - {}",
                        response.statusCode(),response.body(),suggestion.toString());
            }

        } catch (Exception e) {
            logger.error("❌ Error posting review comment", e);
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
