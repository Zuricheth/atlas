package com.qianyu.atlas.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QdrantVectorStoreClient implements VectorStoreClient {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QdrantProperties properties;
    private final HttpClient http;

    public QdrantVectorStoreClient(QdrantProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void upsert(NoteChunk chunk, float[] vector) {
        if (!properties.isEnabled() || vector == null || vector.length == 0) return;
        ensureCollection(vector.length);

        Map<String, Object> body = Map.of("points", List.of(point(chunk, vector)));
        request("PUT", "/collections/" + collection(vector.length) + "/points?wait=true", body);
    }

    @Override
    public void upsertAll(List<VectorUpsertItem> items) {
        if (!properties.isEnabled() || items == null || items.isEmpty()) return;
        Map<Integer, List<VectorUpsertItem>> byDim = new LinkedHashMap<>();
        for (VectorUpsertItem item : items) {
            if (item == null || item.vector() == null || item.vector().length == 0) continue;
            byDim.computeIfAbsent(item.vector().length, ignored -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<Integer, List<VectorUpsertItem>> entry : byDim.entrySet()) {
            int dim = entry.getKey();
            List<VectorUpsertItem> batch = entry.getValue();
            ensureCollection(dim);
            List<Map<String, Object>> points = batch.stream()
                    .map(item -> point(item.chunk(), item.vector()))
                    .toList();
            try {
                request("PUT", "/collections/" + collection(dim) + "/points?wait=true", Map.of("points", points));
            } catch (RuntimeException exception) {
                for (VectorUpsertItem item : batch) {
                    upsert(item.chunk(), item.vector());
                }
            }
        }
    }

    @Override
    public List<VectorSearchResult> search(Long userId, String model, Long providerId, int dim, float[] queryVector, int topK) {
        if (!properties.isEnabled() || queryVector == null || queryVector.length == 0 || dim <= 0) {
            return List.of();
        }
        ensureCollection(dim);

        List<Map<String, Object>> must = new ArrayList<>();
        must.add(match("userId", userId));
        must.add(match("dim", dim));
        if (StringUtils.hasText(model)) {
            must.add(match("model", model));
        }
        if (providerId != null) {
            must.add(match("providerId", providerId));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", queryVector);
        body.put("limit", Math.max(1, Math.min(topK, 50)));
        body.put("with_payload", true);
        body.put("filter", Map.of("must", must));

        JsonNode root = request("POST", "/collections/" + collection(dim) + "/points/search", body);
        JsonNode result = root.path("result");
        if (!result.isArray() || result.isEmpty()) {
            return List.of();
        }

        List<VectorSearchResult> hits = new ArrayList<>();
        for (JsonNode item : result) {
            JsonNode payload = item.path("payload");
            hits.add(new VectorSearchResult(
                    payload.path("chunkId").isMissingNode() ? null : payload.path("chunkId").asLong(),
                    payload.path("noteId").isMissingNode() ? null : payload.path("noteId").asLong(),
                    payload.path("chunkIndex").isMissingNode() ? null : payload.path("chunkIndex").asInt(),
                    payload.path("content").asText(""),
                    (float) item.path("score").asDouble()
            ));
        }
        return hits;
    }

    @Override
    public void deleteByNoteId(Long userId, Long noteId) {
        if (!properties.isEnabled() || userId == null || noteId == null) return;

        Map<String, Object> filter = Map.of("must", List.of(
                match("userId", userId),
                match("noteId", noteId)
        ));
        Map<String, Object> body = Map.of("filter", filter);

        for (int dim : List.of(384, 512, 768, 1024, 1536, 3072)) {
            try {
                request("POST", "/collections/" + collection(dim) + "/points/delete?wait=true", body);
            } catch (Exception exception) {
                log.debug("[Qdrant] skip delete for missing/unavailable collection dim={}, userId={}, noteId={}",
                        dim, userId, noteId, exception);
            }
        }
    }

    private void ensureCollection(int dim) {
        try {
            request("GET", "/collections/" + collection(dim), null);
            return;
        } catch (Exception exception) {
            log.debug("[Qdrant] collection missing before upsert, creating dim={}", dim, exception);
        }

        Map<String, Object> vectors = Map.of(
                "size", dim,
                "distance", "Cosine"
        );
        request("PUT", "/collections/" + collection(dim), Map.of("vectors", vectors));
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private Map<String, Object> point(NoteChunk chunk, float[] vector) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", chunk.getUserId());
        payload.put("noteId", chunk.getNoteId());
        payload.put("chunkId", chunk.getId());
        payload.put("chunkIndex", chunk.getChunkIndex());
        payload.put("content", chunk.getContent());
        payload.put("model", chunk.getModel());
        payload.put("providerId", chunk.getProviderId());
        payload.put("dim", vector.length);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", chunk.getId());
        point.put("vector", vector);
        point.put("payload", payload);
        return point;
    }

    private String collection(int dim) {
        String prefix = StringUtils.hasText(properties.getCollectionPrefix())
                ? properties.getCollectionPrefix().trim()
                : "atlas_note_chunks";
        return prefix + "_" + dim;
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");

            if (StringUtils.hasText(properties.getApiKey())) {
                builder.header("api-key", properties.getApiKey());
            }

            if ("GET".equals(method)) {
                builder.GET();
            } else {
                byte[] bytes = body == null
                        ? new byte[0]
                        : MAPPER.writeValueAsBytes(body);
                builder.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            }

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Qdrant API failed: HTTP " + response.statusCode() + " body=" + response.body());
            }
            if (!StringUtils.hasText(response.body())) {
                return MAPPER.createObjectNode();
            }
            return MAPPER.readTree(response.body());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Qdrant call failed: " + ex.getMessage(), ex);
        }
    }

    private String baseUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException("Qdrant baseUrl is empty");
        }
        return base;
    }
}
