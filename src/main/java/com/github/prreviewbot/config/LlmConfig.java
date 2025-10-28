package com.github.prreviewbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for LLM providers
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {
    
    private String provider = "openai";
    private OpenAi openai = new OpenAi();
    private Anthropic anthropic = new Anthropic();
    private Google google = new Google();
    
    public static class OpenAi {
        private String apiKey;
        private String model = "gpt-4o";
        private int maxTokens = 4000;
        
        // Getters and setters
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }
    
    public static class Anthropic {
        private String apiKey;
        private String model = "claude-3-sonnet-20240229";
        private int maxTokens = 4000;
        
        // Getters and setters
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }
    
    public static class Google {
        private String apiKey;
        private String model = "gemini-pro";
        private int maxTokens = 4000;
        
        // Getters and setters
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }
    
    // Getters and setters
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    
    public OpenAi getOpenai() { return openai; }
    public void setOpenai(OpenAi openai) { this.openai = openai; }
    
    public Anthropic getAnthropic() { return anthropic; }
    public void setAnthropic(Anthropic anthropic) { this.anthropic = anthropic; }
    
    public Google getGoogle() { return google; }
    public void setGoogle(Google google) { this.google = google; }
}
