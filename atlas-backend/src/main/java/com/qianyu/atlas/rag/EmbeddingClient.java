package com.qianyu.atlas.rag;

import java.util.List;

public interface EmbeddingClient {
    int dim();

    String modelName();

    default Long providerId() {
        return null;
    }

    float[] embed(String text);

    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}