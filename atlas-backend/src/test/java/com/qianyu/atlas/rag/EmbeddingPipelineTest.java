package com.qianyu.atlas.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingPipelineTest {

    @Test
    @SuppressWarnings("unchecked")
    void embedAsyncUsesBatchVectorUpsertForPendingChunks() {
        EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
        NoteChunkMapper mapper = mock(NoteChunkMapper.class);
        VectorStoreClient vectorStore = mock(VectorStoreClient.class);
        EmbeddingClient client = new EmbeddingClient() {
            @Override
            public int dim() {
                return 2;
            }

            @Override
            public String modelName() {
                return "test-embedding";
            }

            @Override
            public float[] embed(String text) {
                throw new AssertionError("single embed should not be used on the batch path");
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return List.of(new float[]{1f, 0f}, new float[]{0f, 1f});
            }
        };
        NoteChunk first = chunk(1L, 9L, 0, "alpha");
        NoteChunk second = chunk(2L, 9L, 1, "beta");
        when(factory.current()).thenReturn(client);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        EmbeddingPipeline pipeline = new EmbeddingPipeline(new TextChunker(), factory, mapper, vectorStore);
        pipeline.embedAsync(9L);

        verify(mapper, times(2)).updateById(any(NoteChunk.class));
        verify(vectorStore, never()).upsert(any(NoteChunk.class), any(float[].class));
        ArgumentCaptor<List<VectorUpsertItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).upsertAll(captor.capture());
        List<VectorUpsertItem> upserts = captor.getValue();
        assertEquals(2, upserts.size());
        assertSame(first, upserts.get(0).chunk());
        assertSame(second, upserts.get(1).chunk());
        assertEquals(NoteChunk.STATUS_READY, first.getStatus());
        assertEquals("test-embedding", first.getModel());
        assertEquals(2, first.getDim());
    }

    private NoteChunk chunk(Long id, Long noteId, int index, String content) {
        NoteChunk chunk = new NoteChunk();
        chunk.setId(id);
        chunk.setUserId(7L);
        chunk.setNoteId(noteId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setStatus(NoteChunk.STATUS_PENDING);
        chunk.setVersion(0);
        return chunk;
    }
}
