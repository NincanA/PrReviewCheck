package com.github.prreviewbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for GitHub App
 */
@Configuration
@ConfigurationProperties(prefix = "github")
public class GitHubConfig {
    
    private App app = new App();
    private Bot bot = new Bot();
    
    public static class App {
        private String id;
        private String privateKey;
        private String webhookSecret;
        
        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    }
    
    public static class Bot {
        private String name = "pr-review-bot";
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
    
    // Getters and setters
    public App getApp() { return app; }
    public void setApp(App app) { this.app = app; }
    
    public Bot getBot() { return bot; }
    public void setBot(Bot bot) { this.bot = bot; }
}
