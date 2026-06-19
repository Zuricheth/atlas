package com.qianyu.atlas.rag;

import java.util.Locale;

public final class VectorCodec {
    private VectorCodec() {
    }

    public static String encode(float[] vector) {
        if (vector == null) return null;
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public static float[] decode(String json) {
        if (json == null || json.isBlank() || json.length() < 3) return new float[0];
        String body = json.trim();
        if (body.charAt(0) == '[') body = body.substring(1);
        if (!body.isEmpty() && body.charAt(body.length() - 1) == ']') {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null) return 0f;
        int len = Math.min(a.length, b.length);
        if (len == 0) return 0f;
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}