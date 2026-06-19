package com.qianyu.atlas.rag;

import java.util.List;

public interface VectorStoreClient {
    void upsert(NoteChunk chunk, float[] vector);

    default void upsertAll(List<VectorUpsertItem> items) {
        if (items == null || items.isEmpty()) return;
        for (VectorUpsertItem item : items) {
            upsert(item.chunk(), item.vector());
        }
    }

    List<VectorSearchResult> search(Long userId, String model, Long providerId, int dim, float[] queryVector, int topK);

    void deleteByNoteId(Long userId, Long noteId);
}
