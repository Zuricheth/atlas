package com.qianyu.atlas.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class TextChunker {
    private static final int TARGET = 500;
    private static final int OVERLAP = 50;

    public List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();

        String normalized = text.replace("\r\n", "\n").trim();
        String[] paragraphs = normalized.split("\n{2,}");

        List<String> pieces = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() <= TARGET) {
                pieces.add(trimmed);
            } else {
                pieces.addAll(splitLong(trimmed));
            }
        }
        return mergeWithOverlap(pieces);
    }

    private List<String> splitLong(String paragraph) {
        List<String> sentences = new ArrayList<>(Arrays.asList(paragraph.split("(?<=[。!?；;\\n.!?])")));
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence == null) continue;
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (current.length() + s.length() > TARGET && !current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            if (s.length() > TARGET) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < s.length(); i += TARGET) {
                    result.add(s.substring(i, Math.min(s.length(), i + TARGET)));
                }
            } else {
                current.append(s);
            }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private List<String> mergeWithOverlap(List<String> pieces) {
        if (pieces.size() <= 1) return pieces;
        List<String> merged = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String current = pieces.get(i);
            if (i == 0) {
                merged.add(current);
            } else {
                String previous = pieces.get(i - 1);
                String head = previous.substring(Math.max(0, previous.length() - OVERLAP));
                merged.add(head + current);
            }
        }
        return merged;
    }
}