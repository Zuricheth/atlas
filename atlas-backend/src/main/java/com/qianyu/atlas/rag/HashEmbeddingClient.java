package com.qianyu.atlas.rag;

import java.util.Locale;

/**
 * 本地兜底嵌入实现：把文本按词哈希到固定维度，再 L2 归一化。
 * 当没有配置真实 Embedding provider 时启用，让整条 RAG 链路依然可用。
 */
public class HashEmbeddingClient implements EmbeddingClient {
    private final int dim;

    public HashEmbeddingClient() {
        this(256);
    }

    public HashEmbeddingClient(int dim) {
        this.dim = dim > 0 ? dim : 256;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public String modelName() {
        return "hash-" + dim;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder buffer = new StringBuilder();
        int tokenCount = 0;

        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            boolean isWordChar = Character.isLetterOrDigit(ch);
            if (isWordChar) {
                buffer.append(ch);
                if (ch > 0x4E00) {
                    flush(buffer, vector);
                    tokenCount++;
                }
            } else if (!buffer.isEmpty()) {
                flush(buffer, vector);
                tokenCount++;
            }
        }
        if (!buffer.isEmpty()) {
            flush(buffer, vector);
            tokenCount++;
        }

        if (tokenCount == 0) {
            return vector;
        }

        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private void flush(StringBuilder buffer, float[] vector) {
        if (buffer.isEmpty()) return;
        int hash = buffer.toString().hashCode();
        int index = Math.floorMod(hash, dim);
        int sign = (hash & 1) == 0 ? 1 : -1;
        vector[index] += sign;
        buffer.setLength(0);
    }
}