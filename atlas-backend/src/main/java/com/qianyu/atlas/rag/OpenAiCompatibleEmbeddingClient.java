package com.qianyu.atlas.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dim;
    private final Long providerId;

    private final HttpClient http;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey, String model, int dim, Long providerId) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dim = dim;
        this.providerId = providerId;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public Long providerId() {
        return providerId;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text == null ? "" : text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "input", texts.stream().map(text -> text == null ? "" : text).toList()
            );
            byte[] body = MAPPER.writeValueAsBytes(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizedEndpoint()))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Embedding API failed: HTTP " + response.statusCode() + " body=" + response.body());
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("Embedding API returned empty data: " + response.body());
            }
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) vectors.add(null);
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                int index = item.has("index") ? item.path("index").asInt() : fallbackIndex;
                if (index < 0 || index >= texts.size()) {
                    throw new IllegalStateException("Embedding API returned invalid index: " + response.body());
                }
                vectors.set(index, parseVector(item.path("embedding"), response.body()));
                fallbackIndex++;
            }
            for (float[] vector : vectors) {
                if (vector == null) {
                    throw new IllegalStateException("Embedding API returned incomplete batch: " + response.body());
                }
            }
            return vectors;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Embedding call failed: " + ex.getMessage(), ex);
        }
    }

    private float[] parseVector(JsonNode embedding, String responseBody) {
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding API missing embedding array: " + responseBody);
        }
        float[] vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) embedding.get(i).asDouble();
        }
        if (dim > 0 && vector.length != dim) {
            throw new IllegalStateException(
                    "Embedding dim mismatch: configured=" + dim + " actual=" + vector.length);
        }
        return vector;
    }

    private String normalizedEndpoint() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalStateException("Embedding provider baseUrl is empty");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        if (base.endsWith("/v1")) {
            return base + "/embeddings";
        }
        if (base.endsWith("/embeddings")) {
            return base;
        }
        return base + "/v1/embeddings";
    }
}
