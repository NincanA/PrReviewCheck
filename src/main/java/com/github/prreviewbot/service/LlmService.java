package com.github.prreviewbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.prreviewbot.config.LlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Service for interacting with LLM APIs for code analysis
 */
@Service
public class LlmService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);
    
    private final LlmConfig llmConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public LlmService(LlmConfig llmConfig, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.llmConfig = llmConfig;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }
    
    /**
     * Analyzes code changes and returns review suggestions
     */
    public Mono<List<ReviewSuggestion>> analyzeCodeChanges(String diff, String prTitle, String prDescription) {
        String prompt = buildAnalysisPrompt(diff, prTitle, prDescription);
        
        switch (llmConfig.getProvider().toLowerCase()) {
            case "openai":
                return analyzeWithOpenAI(prompt);
            case "anthropic":
                return analyzeWithAnthropic(prompt);
            case "google":
                return analyzeWithGoogle(prompt);
            default:
                logger.error("Unsupported LLM provider: {}", llmConfig.getProvider());
                return Mono.error(new IllegalArgumentException("Unsupported LLM provider"));
        }
    }
    
    /**
     * Generates a response to a user's question about a review comment
     */
    public Mono<String> generateResponseToQuestion(String question, String originalComment, String codeContext) {
        String prompt = buildQuestionResponsePrompt(question, originalComment, codeContext);
        
        switch (llmConfig.getProvider().toLowerCase()) {
            case "openai":
                return generateResponseWithOpenAI(prompt);
            case "anthropic":
                return generateResponseWithAnthropic(prompt);
            case "google":
                return generateResponseWithGoogle(prompt);
            default:
                return Mono.error(new IllegalArgumentException("Unsupported LLM provider"));
        }
    }

    private Mono<List<ReviewSuggestion>> analyzeWithOpenAI(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", llmConfig.getOpenai().getModel(),
                "prompt", prompt,
                "stream", false
        );

        WebClient webClient = WebClient.create();
        logger.info("🟢 Request body: {}", requestBody);

        return webClient.post()
                .uri("http://localhost:11434/api/generate")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .exchangeToMono(response -> {
                    logger.info("📩 Status: {}", response.statusCode());

                    return response.bodyToMono(String.class)
                            .doOnNext(body -> logger.info("🧾 Raw Ollama Response: {}", body))
                            .map(body -> {
                                if (body == null || body.isBlank()) {
                                    logger.warn("⚠️ Empty response body from Ollama");
                                    return Collections.<ReviewSuggestion>emptyList();
                                }

                                try {

                                    JsonNode json = objectMapper.readTree(body);
                                    if (json.has("done_reason") &&
                                            "load".equalsIgnoreCase(json.get("done_reason").asText())) {
                                        logger.warn("⚠️ Model was still loading — empty response");
                                        return Collections.<ReviewSuggestion>emptyList();
                                    }

                                    String responseText = json.has("response")
                                            ? json.get("response").asText()
                                            : body;

                                    String cleaned = responseText.trim();

                                    int start = cleaned.indexOf('[');
                                    int end = cleaned.lastIndexOf(']');
                                    if (start >= 0 && end >= 0) {
                                        cleaned = cleaned.substring(start, end + 1);
                                    }

                                    cleaned = cleaned
                                            .replaceAll(",\\s*([}\\]])", "$1")
                                            .replaceAll("[^\\x20-\\x7E\\n\\r\\t]", "");

                                    logger.info("🧹 Cleaned response text: {}", cleaned);

                                    if (cleaned.isBlank() || !cleaned.startsWith("[")) {
                                        logger.warn("⚠️ No valid JSON array detected in response");
                                        return Collections.<ReviewSuggestion>emptyList();
                                    }

                                    List<ReviewSuggestion> suggestions = objectMapper.readValue(
                                            cleaned,
                                            new TypeReference<List<ReviewSuggestion>>() {}
                                    );

                                    logger.info("✅ Parsed {} suggestions", suggestions.size());
                                    return suggestions;

                                } catch (Exception e) {
                                    logger.error("❌ Failed to parse Ollama response", e);
                                    return Collections.<ReviewSuggestion>emptyList();
                                }
                            });
                });
    }


    private Mono<List<ReviewSuggestion>> analyzeWithAnthropic(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "model", llmConfig.getAnthropic().getModel(),
            "max_tokens", llmConfig.getAnthropic().getMaxTokens(),
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        
        return webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", llmConfig.getAnthropic().getApiKey())
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> parseAnthropicResponse(response));
    }
    
    private Mono<List<ReviewSuggestion>> analyzeWithGoogle(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of(
                "maxOutputTokens", llmConfig.getGoogle().getMaxTokens(),
                "temperature", 0.1
            )
        );
        
        return webClient.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + 
                     llmConfig.getGoogle().getModel() + ":generateContent?key=" + 
                     llmConfig.getGoogle().getApiKey())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> parseGoogleResponse(response));
    }
    
    private String buildAnalysisPrompt(String diff, String prTitle, String prDescription) {
        return String.format("""
            You are an expert code reviewer. Analyze the following code changes and provide specific, actionable feedback.
            
            PR Title: %s
            PR Description: %s
            
            Code Diff:
            %s
            
            Please provide your analysis in the following JSON format:
            [
                {
                    "file": "path/to/file",
                    "line": 42,
                    "side": "RIGHT",
                    "severity": "WARNING",
                    "category": "BUG",
                    "message": "Specific issue description",
                    "suggestion": "How to fix it"
                }
            ]
            
            Categories: BUG, SECURITY, PERFORMANCE, STYLE, MAINTAINABILITY
            Severity: INFO, WARNING, ERROR
            Side: LEFT (old code), RIGHT (new code)
            
            Focus on:
            1. Potential bugs and logic errors
            2. Security vulnerabilities
            3. Performance issues
            4. Code style and best practices
            5. Maintainability concerns
            
            Be specific and provide actionable suggestions. Only comment on significant issues.
            """, prTitle, prDescription, diff);
    }
    
    private String buildQuestionResponsePrompt(String question, String originalComment, String codeContext) {
        return String.format("""
            A user is asking about a code review comment. Please provide a helpful response.
            
            Original Review Comment: %s
            Code Context: %s
            User Question: %s
            
            Please provide a clear, helpful response that addresses their question.
            """, originalComment, codeContext, question);
    }
    
    private List<ReviewSuggestion> parseOpenAIResponse(JsonNode response) {
        try {
            String content = response.get("choices").get(0).get("message").get("content").asText();
            return objectMapper.readValue(content, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ReviewSuggestion.class));
        } catch (Exception e) {
            logger.error("Error parsing OpenAI response", e);
            return List.of();
        }
    }
    
    private List<ReviewSuggestion> parseAnthropicResponse(JsonNode response) {
        try {
            String content = response.get("content").get(0).get("text").asText();
            return objectMapper.readValue(content, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ReviewSuggestion.class));
        } catch (Exception e) {
            logger.error("Error parsing Anthropic response", e);
            return List.of();
        }
    }
    
    private List<ReviewSuggestion> parseGoogleResponse(JsonNode response) {
        try {
            String content = response.get("candidates").get(0).get("content").get("parts").get(0).get("text").asText();
            return objectMapper.readValue(content, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ReviewSuggestion.class));
        } catch (Exception e) {
            logger.error("Error parsing Google response", e);
            return List.of();
        }
    }
    
    private Mono<String> generateResponseWithOpenAI(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "model", llmConfig.getOpenai().getModel(),
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 500,
            "temperature", 0.3
        );
        
        return webClient.post()
                .uri("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer " + llmConfig.getOpenai().getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> response.get("choices").get(0).get("message").get("content").asText());
    }
    
    private Mono<String> generateResponseWithAnthropic(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "model", llmConfig.getAnthropic().getModel(),
            "max_tokens", 500,
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        
        return webClient.post()
                .uri("https://api.anthropic.com/v1/messages")
                .header("x-api-key", llmConfig.getAnthropic().getApiKey())
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> response.get("content").get(0).get("text").asText());
    }
    
    private Mono<String> generateResponseWithGoogle(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of(
                "maxOutputTokens", 500,
                "temperature", 0.3
            )
        );
        
        return webClient.post()
                .uri("https://generativelanguage.googleapis.com/v1beta/models/" + 
                     llmConfig.getGoogle().getModel() + ":generateContent?key=" + 
                     llmConfig.getGoogle().getApiKey())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> response.get("candidates").get(0).get("content").get("parts").get(0).get("text").asText());
    }
    
    /**
     * Data class for review suggestions
     */
    public static class ReviewSuggestion {
        private String file;
        private int line;
        private String side;
        private String severity;
        private String category;
        private String message;
        private String suggestion;
        
        // Getters and setters
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        
        public int getLine() { return line; }
        public void setLine(int line) { this.line = line; }
        
        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }
        
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public String getSuggestion() { return suggestion; }
        public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    }
}
