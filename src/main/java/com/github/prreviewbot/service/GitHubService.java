package com.github.prreviewbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for interacting with GitHub API
 */
@Service
public class GitHubService {
    
    private static final Logger logger = LoggerFactory.getLogger(GitHubService.class);
    
    private final GitHubConfig githubConfig;
    
    @Autowired
    public GitHubService(GitHubConfig githubConfig) {
        this.githubConfig = githubConfig;
    }
    
    /**
     * Creates authenticated GitHub client for app installation
     */
    public GitHub createGitHubClient(long installationId) throws IOException {
        GitHubBuilder builder = new GitHubBuilder()
                .withAppInstallationToken(installationId)
                .withJwtToken(createJwtToken());
        
        return builder.build();
    }
    
    /**
     * Fetches PR diff for analysis
     */
    public String getPrDiff(GitHub github, String owner, String repo, int prNumber) throws IOException {
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        
        return pullRequest.getDiff();
    }
    
    /**
     * Gets changed files in a PR
     */
    public List<GHFile> getChangedFiles(GitHub github, String owner, String repo, int prNumber) throws IOException {
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        
        return pullRequest.listFiles().toList();
    }
    
    /**
     * Posts a review comment on a specific line
     */
    public void postReviewComment(GitHub github, String owner, String repo, int prNumber, 
                                 String body, String path, int line, String side) throws IOException {
        GHRepository repository = github.getRepository(owner + "/" + repo);
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);
        
        GHPullRequestReviewComment comment = pullRequest.createReviewComment(
                body, pullRequest.getHead().getSha(), path, line, side);
        
        logger.info("Posted review comment: {}", comment.getHtmlUrl());
    }
    
    /**
     * Posts a reply to an existing review comment
     */
    public void replyToReviewComment(GitHub github, long commentId, String body) throws IOException {
        GHPullRequestReviewComment comment = github.getPullRequestReviewComment(commentId);
        comment.reply(body);
        
        logger.info("Posted reply to comment: {}", comment.getHtmlUrl());
    }
    
    /**
     * Creates JWT token for GitHub App authentication
     */
    private String createJwtToken() {
        try {
            // This is a simplified version - in production, you'd use a proper JWT library
            // For now, we'll use the GitHub API library's built-in JWT creation
            return GitHub.connectUsingApp(githubConfig.getApp().getId(), 
                                        githubConfig.getApp().getPrivateKey())
                         .getAppInstallationToken(githubConfig.getApp().getId())
                         .getToken();
        } catch (IOException e) {
            logger.error("Error creating JWT token", e);
            throw new RuntimeException("Failed to create JWT token", e);
        }
    }
    
    /**
     * Extracts repository information from PR
     */
    public RepositoryInfo extractRepositoryInfo(JsonNode pullRequest) {
        String htmlUrl = pullRequest.get("html_url").asText();
        String[] parts = htmlUrl.split("/");
        String owner = parts[3];
        String repo = parts[4];
        int prNumber = pullRequest.get("number").asInt();
        
        return new RepositoryInfo(owner, repo, prNumber);
    }
    
    /**
     * Data class for repository information
     */
    public static class RepositoryInfo {
        private final String owner;
        private final String repo;
        private final int prNumber;
        
        public RepositoryInfo(String owner, String repo, int prNumber) {
            this.owner = owner;
            this.repo = repo;
            this.prNumber = prNumber;
        }
        
        public String getOwner() { return owner; }
        public String getRepo() { return repo; }
        public int getPrNumber() { return prNumber; }
    }
}
