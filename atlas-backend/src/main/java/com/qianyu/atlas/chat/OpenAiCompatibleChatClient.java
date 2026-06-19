package com.qianyu.atlas.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAiCompatibleChatClient implements ChatClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Long providerId;
    private final HttpClient http;

    public OpenAiCompatibleChatClient(String baseUrl, String apiKey, String model, Long providerId) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.providerId = providerId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String complete(List<Message> messages) {
        try {
            List<Map<String, String>> payloadMessages = messages.stream()
                    .map(message -> Map.of(
                            "role", message.role(),
                            "content", message.content() == null ? "" : message.content()
                    ))
                    .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", payloadMessages);
            payload.put("temperature", 0.2);
            payload.put("stream", false);

            byte[] body = MAPPER.writeValueAsBytes(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedEndpoint()))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(300))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Chat API failed: HTTP " + response.statusCode() + " body=" + response.body());
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("Chat API returned empty choices: " + response.body());
            }

            String content = choices.get(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                content = choices.get(0).path("text").asText("");
            }
            return content == null ? "" : content;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Chat call failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void completeStream(List<Message> messages, Consumer<String> onDelta) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                List<Map<String, String>> payloadMessages = messages.stream()
                        .map(message -> Map.of(
                                "role", message.role(),
                                "content", message.content() == null ? "" : message.content()
                        ))
                        .toList();

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model);
                payload.put("messages", payloadMessages);
                payload.put("temperature", 0.2);
                payload.put("stream", true);

                byte[] body = MAPPER.writeValueAsBytes(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(normalizedEndpoint()))
                        .version(HttpClient.Version.HTTP_1_1)
                        .timeout(Duration.ofSeconds(300))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                HttpResponse<java.util.stream.Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
                int status = response.statusCode();
                // 429 / 5xx 可重试; 其他非 2xx 直接抛
                if (status == 429 || status >= 500) {
                    throw new RetryableHttpException(status);
                }
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Chat stream API failed: HTTP " + status);
                }

                // try-with-resources 确保 SSE 行流在消费完或抛异常时都被关闭, 释放底层连接
                try (java.util.stream.Stream<String> lines = response.body()) {
                    lines.forEach(line -> handleStreamLine(line, onDelta));
                }
                return;
            } catch (RetryableHttpException ex) {
                if (attempt >= MAX_RETRY) {
                    throw new IllegalStateException("Chat stream API failed after " + attempt + " attempts: HTTP " + ex.status, ex);
                }
                log.warn("[Chat] stream got HTTP {}, retry {}/{} model={}", ex.status, attempt, MAX_RETRY, model);
                sleepBackoff(attempt);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("Chat stream call failed: " + ex.getMessage(), ex);
            }
        }
    }

    private static final int MAX_RETRY = 3;

    private static class RetryableHttpException extends RuntimeException {
        final int status;
        RetryableHttpException(int status) { super("HTTP " + status); this.status = status; }
    }

    private void sleepBackoff(int attempt) {
        long base = 300L * (long) Math.pow(2, Math.max(0, attempt - 1));
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 200);
        try {
            Thread.sleep(Math.min(8_000L, base) + jitter);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleStreamLine(String line, Consumer<String> onDelta) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return;
        if (!trimmed.startsWith("data:")) return;
        String data = trimmed.substring("data:".length()).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        try {
            JsonNode root = MAPPER.readTree(data);
            JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0)
                    : null;
            if (choice == null) return;
            String content = choice.path("delta").path("content").asText(null);
            if (content == null || content.isEmpty()) {
                content = choice.path("text").asText("");
            }
            if (content != null && !content.isEmpty()) {
                onDelta.accept(content);
            }
        } catch (Exception exception) {
            log.debug("[Chat] ignore malformed stream metadata line from model={}", model, exception);
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Long providerId() {
        return providerId;
    }

    private String normalizedEndpoint() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalStateException("Chat provider baseUrl is empty");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/embeddings")) {
            base = base.substring(0, base.length() - "/embeddings".length());
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/v1/chat/completions";
    }
}
