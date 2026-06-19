package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SemanticRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(SemanticRetrievalService.class);
    private static final int MYSQL_FALLBACK_LIMIT = 3000;

    // 查询级 embedding 缓存：key = clientCacheKey + ":" + dim + ":" + normalizedQuery
    // 同一查询文本 + 同一模型 → 命中。embedding 输出和 user 无关，全用户共享缓存。
    // hash fallback 不写缓存，避免污染真模型结果。
    private static final int EMBED_CACHE_CAPACITY = 200;
    private static final long EMBED_CACHE_TTL_MILLIS = 5L * 60L * 1000L;

    private final Map<String, CacheEntry> embedCache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > EMBED_CACHE_CAPACITY;
                }
            });

    private final EmbeddingClientFactory embeddingClientFactory;
    private final NoteChunkMapper noteChunkMapper;
    private final NoteMapper noteMapper;
    private final VectorStoreClient vectorStoreClient;

    public SemanticRetrievalService(EmbeddingClientFactory embeddingClientFactory,
                                    NoteChunkMapper noteChunkMapper,
                                    NoteMapper noteMapper,
                                    VectorStoreClient vectorStoreClient) {
        this.embeddingClientFactory = embeddingClientFactory;
        this.noteChunkMapper = noteChunkMapper;
        this.noteMapper = noteMapper;
        this.vectorStoreClient = vectorStoreClient;
    }

    public List<SearchHit> semanticHits(Long userId, String query, int limit) {
        if (!StringUtils.hasText(query)) return List.of();

        EmbeddingContext context = embeddingContext(query);
        if (context.degraded()) {
            // embedding 不可用 → hybrid 退化到纯 keyword
            return List.of();
        }
        List<VectorSearchResult> results = vectorStoreClient.search(
                userId,
                context.client().modelName(),
                context.client().providerId(),
                context.dim(),
                context.queryVector(),
                limit
        );
        results = filterActiveVectorResults(userId, results);
        if (results.isEmpty()) {
            results = semanticChunksFromMysql(userId, context, limit);
        }
        return toSearchHits(userId, results, limit);
    }

    public List<VectorSearchResult> retrieveChunks(Long userId, String query, int limit) {
        if (!StringUtils.hasText(query)) return List.of();

        EmbeddingContext context = embeddingContext(query);
        if (context.degraded()) {
            return List.of();
        }
        List<VectorSearchResult> results = vectorStoreClient.search(
                userId,
                context.client().modelName(),
                context.client().providerId(),
                context.dim(),
                context.queryVector(),
                limit
        );
        results = filterActiveVectorResults(userId, results);
        if (!results.isEmpty()) return results;
        return semanticChunksFromMysql(userId, context, limit);
    }

    private EmbeddingContext embeddingContext(String query) {
        EmbeddingClient client = embeddingClientFactory.current();
        try {
            float[] vec = cachedEmbed(query, client);
            return new EmbeddingContext(client, vec, client.dim(), false);
        } catch (Exception exception) {
            log.warn("[RAG] embed failed, degrading to keyword-only: {}", exception.getMessage());
            return new EmbeddingContext(client, null, client.dim(), true);
        }
    }

    /**
     * 查询级 embedding 缓存：避免一次搜索内 hybrid+tree 重复 embed 同一查询文本。
     * hash fallback 客户端不写缓存。
     */
    private float[] cachedEmbed(String query, EmbeddingClient client) {
        if (client instanceof HashEmbeddingClient) {
            return client.embed(query);
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String cacheKey = clientCacheKey(client) + ":" + client.dim() + ":" + normalized;
        long now = System.currentTimeMillis();
        CacheEntry hit = embedCache.get(cacheKey);
        if (hit != null && hit.expireAt > now) {
            return hit.vec;
        }
        if (hit != null) {
            embedCache.remove(cacheKey);
        }
        float[] vec = client.embed(query);
        embedCache.put(cacheKey, new CacheEntry(vec, now + EMBED_CACHE_TTL_MILLIS));
        return vec;
    }

    private String clientCacheKey(EmbeddingClient client) {
        String model = client.modelName() == null ? "" : client.modelName();
        Long providerId = client.providerId();
        return model + "@" + (providerId == null ? "0" : providerId);
    }

    private List<SearchHit> toSearchHits(Long userId, List<VectorSearchResult> results, int limit) {
        if (results.isEmpty()) return List.of();
        Map<Long, SearchHit> bestByNote = new LinkedHashMap<>();
        for (VectorSearchResult result : results) {
            if (result.noteId() == null || bestByNote.containsKey(result.noteId())) continue;
            bestByNote.put(result.noteId(), new SearchHit(
                    result.noteId(),
                    null,
                    snippet(result.content()),
                    result.score(),
                    "semantic-qdrant"
            ));
            if (bestByNote.size() >= limit) break;
        }
        return fillTitles(userId, bestByNote);
    }

    private List<VectorSearchResult> semanticChunksFromMysql(Long userId, EmbeddingContext context, int limit) {
        List<NoteChunk> chunks = noteChunkMapper.selectList(new LambdaQueryWrapper<NoteChunk>()
                .eq(NoteChunk::getUserId, userId)
                .eq(NoteChunk::getStatus, NoteChunk.STATUS_READY)
                .eq(context.dim() > 0, NoteChunk::getDim, context.dim())
                .eq(StringUtils.hasText(context.client().modelName()), NoteChunk::getModel, context.client().modelName())
                .eq(context.client().providerId() != null, NoteChunk::getProviderId, context.client().providerId())
                .orderByDesc(NoteChunk::getId)
                .last("limit " + MYSQL_FALLBACK_LIMIT));
        if (chunks.size() >= MYSQL_FALLBACK_LIMIT) {
            log.warn("[RAG] mysql semantic fallback truncated userId={} chunks={} limit={}", userId, chunks.size(), MYSQL_FALLBACK_LIMIT);
        }

        record Scored(NoteChunk chunk, float score) {
        }

        List<Scored> scored = new ArrayList<>(chunks.size());
        for (NoteChunk chunk : chunks) {
            float[] vec = VectorCodec.decode(chunk.getEmbedding());
            scored.add(new Scored(chunk, VectorCodec.cosine(context.queryVector(), vec)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<VectorSearchResult> results = new ArrayList<>();
        Set<Long> activeNoteIds = activeNoteIds(userId, chunks.stream().map(NoteChunk::getNoteId).collect(java.util.stream.Collectors.toSet()));
        for (Scored scoredChunk : scored) {
            if (!activeNoteIds.contains(scoredChunk.chunk().getNoteId())) continue;
            results.add(new VectorSearchResult(
                    scoredChunk.chunk().getId(),
                    scoredChunk.chunk().getNoteId(),
                    scoredChunk.chunk().getChunkIndex(),
                    scoredChunk.chunk().getContent(),
                    scoredChunk.score()
            ));
            if (results.size() >= limit) break;
        }
        return results;
    }

    private List<SearchHit> fillTitles(Long userId, Map<Long, SearchHit> bestByNote) {
        if (bestByNote.isEmpty()) return List.of();
        Set<Long> ids = bestByNote.keySet();
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .in(Note::getId, ids));
        Map<Long, String> titles = new HashMap<>();
        for (Note note : notes) titles.put(note.getId(), note.getTitle());

        List<SearchHit> result = new ArrayList<>(bestByNote.size());
        for (SearchHit hit : bestByNote.values()) {
            if (!titles.containsKey(hit.noteId())) continue;
            result.add(new SearchHit(
                    hit.noteId(),
                    titles.get(hit.noteId()),
                    hit.snippet(),
                    hit.score(),
                    hit.source()
            ));
        }
        return result;
    }

    private List<VectorSearchResult> filterActiveVectorResults(Long userId, List<VectorSearchResult> results) {
        if (results.isEmpty()) return results;
        Set<Long> ids = new HashSet<>();
        for (VectorSearchResult result : results) {
            if (result.noteId() != null) ids.add(result.noteId());
        }
        Set<Long> activeIds = activeNoteIds(userId, ids);
        List<VectorSearchResult> filtered = new ArrayList<>();
        for (VectorSearchResult result : results) {
            if (activeIds.contains(result.noteId())) filtered.add(result);
        }
        return filtered;
    }

    private Set<Long> activeNoteIds(Long userId, Set<Long> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) return Set.of();
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .in(Note::getId, noteIds));
        Set<Long> activeIds = new HashSet<>();
        for (Note note : notes) activeIds.add(note.getId());
        return activeIds;
    }

    private String snippet(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "..." : cleaned;
    }

    private record EmbeddingContext(EmbeddingClient client, float[] queryVector, int dim, boolean degraded) {
    }

    private record CacheEntry(float[] vec, long expireAt) {
    }
}
