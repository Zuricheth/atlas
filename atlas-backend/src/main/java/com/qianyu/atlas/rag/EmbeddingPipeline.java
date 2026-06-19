package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class EmbeddingPipeline {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingPipeline.class);
    private static final int MAX_RETRY = 3;

    private final TextChunker textChunker;
    private final EmbeddingClientFactory embeddingClientFactory;
    private final NoteChunkMapper noteChunkMapper;
    private final VectorStoreClient vectorStoreClient;

    public EmbeddingPipeline(TextChunker textChunker,
                             EmbeddingClientFactory embeddingClientFactory,
                             NoteChunkMapper noteChunkMapper,
                             VectorStoreClient vectorStoreClient) {
        this.textChunker = textChunker;
        this.embeddingClientFactory = embeddingClientFactory;
        this.noteChunkMapper = noteChunkMapper;
        this.vectorStoreClient = vectorStoreClient;
    }

    @Transactional
    public void rebuildChunks(Long userId, Long noteId, String title, String content) {
        noteChunkMapper.delete(new LambdaQueryWrapper<NoteChunk>().eq(NoteChunk::getNoteId, noteId));
        vectorStoreClient.deleteByNoteId(userId, noteId);

        String composed = (title == null ? "" : title) + "\n\n" + (content == null ? "" : content);
        List<String> pieces = textChunker.split(composed);
        if (pieces.isEmpty()) return;

        for (int i = 0; i < pieces.size(); i++) {
            NoteChunk chunk = new NoteChunk();
            chunk.setUserId(userId);
            chunk.setNoteId(noteId);
            chunk.setChunkIndex(i);
            chunk.setContent(pieces.get(i));
            chunk.setStatus(NoteChunk.STATUS_PENDING);
            chunk.setVersion(0);
            noteChunkMapper.insert(chunk);
        }
    }

    @Transactional
    public void deleteIndex(Long userId, Long noteId) {
        noteChunkMapper.delete(new LambdaQueryWrapper<NoteChunk>().eq(NoteChunk::getNoteId, noteId));
        vectorStoreClient.deleteByNoteId(userId, noteId);
    }

    @Async("embeddingExecutor")
    public void embedAsync(Long noteId) {
        EmbeddingClient client = embeddingClientFactory.current();
        List<NoteChunk> chunks = noteChunkMapper.selectList(new LambdaQueryWrapper<NoteChunk>()
                .eq(NoteChunk::getNoteId, noteId)
                .eq(NoteChunk::getStatus, NoteChunk.STATUS_PENDING));
        if (chunks.isEmpty()) return;

        log.info("[RAG] start embedding noteId={} chunks={} model={} dim={}",
                noteId, chunks.size(), client.modelName(), client.dim());
        List<float[]> vectors = embedBatch(client, chunks);
        if (vectors == null) {
            for (NoteChunk chunk : chunks) {
                embedSingle(client, chunk);
            }
        } else {
            List<VectorUpsertItem> upserts = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                NoteChunk chunk = chunks.get(i);
                float[] vector = vectors.get(i);
                markReady(client, chunk, vector);
                noteChunkMapper.updateById(chunk);
                upserts.add(new VectorUpsertItem(chunk, vector));
            }
            vectorStoreClient.upsertAll(upserts);
        }
        log.info("[RAG] finish embedding noteId={}", noteId);
    }

    /**
     * 调度 embedAsync, 但若当前在事务中, 推迟到事务提交后再 dispatch,
     * 避免异步线程读到尚未提交的 chunks (脏读 / 漏读).
     * 调用方在事务内或事务外都可以调用此方法, 行为一致.
     */
    public void scheduleEmbedAfterCommit(Long noteId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    embedAsync(noteId);
                }
            });
        } else {
            embedAsync(noteId);
        }
    }

    private List<float[]> embedBatch(EmbeddingClient client, List<NoteChunk> chunks) {
        int attempt = 0;
        List<String> texts = chunks.stream().map(NoteChunk::getContent).toList();
        while (attempt < MAX_RETRY) {
            try {
                List<float[]> vectors = client.embedBatch(texts);
                if (vectors.size() != chunks.size()) {
                    throw new IllegalStateException("Embedding batch size mismatch: chunks="
                            + chunks.size() + " vectors=" + vectors.size());
                }
                return vectors;
            } catch (Exception ex) {
                attempt++;
                log.warn("[RAG] batch embed failed noteId={} chunks={} attempt={} error={}",
                        chunks.get(0).getNoteId(), chunks.size(), attempt, ex.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        return null;
    }

    private void embedSingle(EmbeddingClient client, NoteChunk chunk) {
        int attempt = 0;
        while (attempt < MAX_RETRY) {
            try {
                float[] vector = client.embed(chunk.getContent());
                markReady(client, chunk, vector);
                noteChunkMapper.updateById(chunk);
                vectorStoreClient.upsert(chunk, vector);
                return;
            } catch (Exception ex) {
                attempt++;
                log.warn("[RAG] embed failed chunkId={} attempt={} error={}", chunk.getId(), attempt, ex.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        chunk.setStatus(NoteChunk.STATUS_FAILED);
        noteChunkMapper.updateById(chunk);
    }

    private void markReady(EmbeddingClient client, NoteChunk chunk, float[] vector) {
        chunk.setEmbedding(VectorCodec.encode(vector));
        chunk.setDim(vector.length);
        chunk.setModel(client.modelName());
        chunk.setProviderId(client.providerId());
        chunk.setStatus(NoteChunk.STATUS_READY);
        chunk.setVersion(chunk.getVersion() == null ? 1 : chunk.getVersion() + 1);
    }

    private void sleepBeforeRetry(int attempt) {
        // 指数退避: 300ms, 900ms, 2.7s ... 加 0-200ms 抖动避免雪崩
        long base = 300L * (long) Math.pow(3, Math.max(0, attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 200);
        try {
            Thread.sleep(Math.min(10_000L, base) + jitter);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
