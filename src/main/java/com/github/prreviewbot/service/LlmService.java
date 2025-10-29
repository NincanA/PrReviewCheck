package com.github.prreviewbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.prreviewbot.config.LlmConfig;
import com.github.prreviewbot.utils.DiffUtils;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.PagedIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        this.webClient = WebClient.builder()
                .baseUrl("http://localhost:11434")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }
    
    /**
     * Analyzes code changes and returns review suggestions
     */
    public Mono<List<ReviewSuggestion>> analyzeCodeChanges(
            PagedIterable<GHPullRequestFileDetail> diffContentByFile,
            String prTitle,
            String prDescription) {

        List<Mono<List<ReviewSuggestion>>> perFileMonos = new ArrayList<>();

        for (GHPullRequestFileDetail fileDetail : diffContentByFile) {
            if (fileDetail.getPatch() == null || fileDetail.getPatch().isBlank()) {
                logger.info("⚠️ Skipping {} — no patch found", fileDetail.getFilename());
                continue;
            }

            Mono<List<ReviewSuggestion>> suggestionsMono;

            switch (llmConfig.getProvider().toLowerCase()) {
                case "ollama":
                    suggestionsMono = analyzeWithOpenAI(fileDetail, prTitle, prDescription)
                            .doOnSubscribe(sub -> logger.info("🚀 Analyzing file: {}", fileDetail.getFilename()))
                            .doOnError(err -> logger.error("❌ Analysis failed for {}: {}", fileDetail.getFilename(), err.getMessage()))
                            .onErrorReturn(Collections.emptyList());
                    break;

                default:
                    logger.error("Unsupported LLM provider: {}", llmConfig.getProvider());
                    return Mono.error(new IllegalArgumentException("Unsupported LLM provider: " + llmConfig.getProvider()));
            }

            perFileMonos.add(suggestionsMono);
        }

        if (perFileMonos.isEmpty()) {
            logger.warn("⚠️ No diffs found to analyze");
            return Mono.just(Collections.emptyList());
        }

        // 🧠 Merge all file analyses concurrently and collect all suggestions
        return Flux.merge(perFileMonos)
                .flatMap(Flux::fromIterable)
                .collectList()
                .doOnNext(list -> logger.info("✅ Total {} review suggestions generated", list.size()));
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

    private List<ReviewSuggestion> parseOllamaResponse(String body, String filePath) {
        if (body == null || body.isBlank()) {
            logger.warn("⚠️ Empty response for {}", filePath);
            return Collections.emptyList();
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            String responseText = json.has("response") ? json.get("response").asText() : body;
            String cleaned = responseText.trim();
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end >= 0) {
                cleaned = cleaned.substring(start, end + 1);
            }
            cleaned = cleaned
                    .replaceAll(",\\s*([}\\]])", "$1")
                    .replaceAll("[^\\x20-\\x7E\\n\\r\\t]", "")
                    .trim();

            if (cleaned.isBlank() || !cleaned.startsWith("[")) {
                logger.warn("⚠️ No valid JSON array detected in response for {}", filePath);
                return Collections.emptyList();
            }
            List<ReviewSuggestion> suggestions = objectMapper.readValue(
                    cleaned,
                    new TypeReference<List<ReviewSuggestion>>() {}
            );
            for (ReviewSuggestion s : suggestions) {
                s.setFile(filePath);
                if (s.getLine() <= 0) s.setLine(0);
                if (s.getSide() == null || s.getSide().isBlank()) s.setSide("RIGHT");
                if (s.getSeverity() == null) s.setSeverity("INFO");
                if (s.getCategory() == null) s.setCategory("MAINTAINABILITY");
            }

            return suggestions;

        } catch (Exception e) {
            logger.error("❌ Failed to parse Ollama response for {}: {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }


    private Mono<List<ReviewSuggestion>> analyzeWithOpenAI(GHPullRequestFileDetail fileDetail,
                                                           String prTitle,
                                                           String prDescription) {
        String patch = fileDetail.getPatch();
        if (patch == null || patch.isBlank()) {
            logger.warn("⚠️ Skipping file {} — empty patch", fileDetail.getFilename());
            return Mono.just(Collections.emptyList());
        }

        List<DiffUtils.PatchHunk> hunks = parseHunks(patch);

        return Flux.fromIterable(hunks)
                .flatMap(hunk -> analyzeHunk(fileDetail, hunk, prTitle, prDescription))
                .flatMapIterable(suggestions -> suggestions)
                .collectList();
    }

    private Mono<List<ReviewSuggestion>> analyzeHunk(GHPullRequestFileDetail fileDetail,
                                                     DiffUtils.PatchHunk hunk,
                                                     String prTitle,
                                                     String prDescription) {
        String prompt = buildAnalysisPrompt(hunk.getContent(), prTitle, prDescription);

        return sendPromptWithRetry(fileDetail.getFilename(), prompt, hunk, 1);
    }

    private List<DiffUtils.PatchHunk> parseHunks(String patch) {
        List<DiffUtils.PatchHunk> hunks = new ArrayList<>();
        Matcher matcher = Pattern.compile("@@ -(\\d+),?\\d* \\+(\\d+),?\\d* @@").matcher(patch);
        int lastEnd = 0;
        int currentStartLine = 1;

        while (matcher.find()) {
            if (lastEnd != 0) {
                String hunkContent = patch.substring(lastEnd, matcher.start());
                hunks.add(new DiffUtils.PatchHunk(currentStartLine, hunkContent));
            }

            currentStartLine = Integer.parseInt(matcher.group(2));
            lastEnd = matcher.end();
        }

        if (lastEnd < patch.length()) {
            String hunkContent = patch.substring(lastEnd);
            hunks.add(new DiffUtils.PatchHunk(currentStartLine, hunkContent));
        }

        return hunks;
    }

    private Mono<List<ReviewSuggestion>>  sendPromptWithRetry(String fileName,
                                                             String prompt,
                                                             DiffUtils.PatchHunk patchHunk,
                                                             int attempt) {

        if (patchHunk.getContent() == null || patchHunk.getContent().isBlank()) {
            logger.warn("⚠️ Skipping empty patch hunk for file {}", fileName);
            return Mono.just(Collections.emptyList());
        }

        Map<String, Object> requestBody = Map.of(
                "model", llmConfig.getOpenai().getModel(),
                "prompt", prompt,
                "stream", false
        );

        logger.info("🟢 [Attempt {}] Sending prompt for file: {} (starting at line {})",
                attempt, fileName, patchHunk.getStartNewLine());

        return webClient.post()
                .uri("/api/generate")
                .bodyValue(requestBody)
                .exchangeToMono(response -> {
                    logger.info("📩 Response status for {}: {}", fileName, response.statusCode());
                    return response.bodyToMono(String.class);
                })
                .flatMap(body -> {
                    List<ReviewSuggestion> parsed = parseOllamaResponse(body, fileName);

                    // Attach line metadata (safe fallback)
                    parsed.forEach(s -> attachLineMetadata(s, fileName, patchHunk));

                    if (parsed.isEmpty() && attempt < 5) {
                        String retryPrompt = """
                        The previous response was invalid or not JSON formatted.
                        Respond ONLY with a valid JSON array like:
                        [
                          {
                            "category": "BUG",
                            "severity": "WARNING",
                            "message": "Describe the issue",
                            "suggestion": "How to fix it"
                          }
                        ]

                        Do not include anything outside the JSON.

                        Here's the diff hunk again for file %s (starting at line %d):
                        %s
                        """.formatted(fileName, patchHunk.getStartNewLine(), patchHunk.getContent());

                        logger.warn("⚠️ Invalid response (attempt {}) for {} — retrying...",
                                attempt, fileName);

                        return Mono.delay(Duration.ofSeconds((long) Math.pow(2, attempt)))
                                .flatMap(__ -> sendPromptWithRetry(fileName, retryPrompt, patchHunk, attempt + 1));
                    }

                    return Mono.just(parsed);
                })
                .onErrorResume(e -> {
                    logger.error("❌ Ollama request failed for {} (attempt {}): {}", fileName, attempt, e.getMessage());
                    if (attempt < 5) {
                        long delay = (long) Math.pow(2, attempt);
                        logger.info("🔁 Retrying {}/5 for {} after {}s", attempt + 1, fileName, delay);
                        return Mono.delay(Duration.ofSeconds(delay))
                                .flatMap(__ -> sendPromptWithRetry(fileName, prompt, patchHunk, attempt + 1));
                    }
                    return Mono.just(Collections.emptyList());
                });
    }

    private void attachLineMetadata(ReviewSuggestion suggestion, String fileName, DiffUtils.PatchHunk patchHunk) {
        int lineNumber = suggestion.getLine();

        if (lineNumber <= 0) {
            lineNumber = inferFirstAddedLine(patchHunk);
            if(lineNumber <= 0) {
                lineNumber = 1;
            }
        }

        suggestion.setFile(fileName);
        suggestion.setSide("RIGHT");
        suggestion.setLine(lineNumber);
        logger.error("✅ for file given: {} : Parsed  suggestions {}", fileName, suggestion);
    }
    private int inferFirstAddedLine(DiffUtils.PatchHunk hunk) {
        int newLine = hunk.getStartNewLine();
        for (String line : hunk.getContent().split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return newLine; // first added line
            }
            if (!line.startsWith("-")) {
                newLine++; // only increment for context and added lines
            }
        }
        return hunk.getStartNewLine();
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
        You are an expert code reviewer.

        Analyze the following code changes and return your response as **valid JSON only**.

        PR Title: %s
        PR Description: %s

        Code Diff:
        %s

        Output must strictly follow this format — no markdown, no explanations, no text before or after:

        [
            {
                "file": "string, path to file (or null if unknown)",
                "line": number (line number if available, else null),
                "side": "LEFT or RIGHT",
                "severity": "INFO | WARNING | ERROR",
                "category": "BUG | SECURITY | PERFORMANCE | STYLE | MAINTAINABILITY",
                "message": "Short description of the issue",
                "suggestion": "Concrete, actionable fix or improvement"
            }
        ]

        Requirements:
        - Return a valid JSON array.
        - Do not include any extra commentary, markdown formatting, or text outside the JSON.
        - If no issues are found, return an empty array: []
        - Make sure extract the exact file names from the diff and the exact line numbers
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

        @Override
        public String toString() {
            return "ReviewSuggestion{" +
                    "file='" + file + '\'' +
                    ", line=" + line +
                    ", side='" + side + '\'' +
                    ", severity='" + severity + '\'' +
                    ", category='" + category + '\'' +
                    ", message='" + message + '\'' +
                    ", suggestion='" + suggestion + '\'' +
                    '}';
        }
    }


}
