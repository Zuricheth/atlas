package com.qianyu.atlas.rag;

import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.notebook.Notebook;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class SearchTextSupport {
    static final int DEFAULT_TOP_K = 10;
    static final int DETAIL_LIMIT = 30;
    static final int EXCERPTS_PER_NOTE = 8;
    static final int EXCERPT_RADIUS = 90;

    private SearchTextSupport() {
    }

    static int topK(Integer value) {
        return value == null ? DEFAULT_TOP_K : Math.min(Math.max(value, 1), 50);
    }

    static int detailLimit(Integer value) {
        return value == null ? DETAIL_LIMIT : Math.min(Math.max(value, 1), 100);
    }

    static int treeLimit(Integer value) {
        return value == null ? 120 : Math.min(Math.max(value, 10), 300);
    }

    static HybridScore hybridScore(Note note,
                                   String notebookPath,
                                   String normalizedQuery,
                                   List<String> terms,
                                   SearchHit semanticHit) {
        String title = normalize(note.getTitle());
        String summary = normalize(note.getSummary());
        String content = normalize(note.getContent());
        String searchText = normalize(note.getSearchText());
        String path = normalize(notebookPath);
        String fullText = String.join(" ", title, summary, content, searchText, path);

        float exactScore = 0f;
        if (StringUtils.hasText(normalizedQuery)) {
            if (title.equals(normalizedQuery)) exactScore += 28f;
            else if (title.contains(normalizedQuery)) exactScore += 18f;
            if (path.contains(normalizedQuery)) exactScore += 14f;
            if (summary.contains(normalizedQuery)) exactScore += 10f;
            if (content.contains(normalizedQuery)) exactScore += 8f;
            else if (searchText.contains(normalizedQuery)) exactScore += 6f;
            exactScore += Math.min(8f, occurrenceCount(fullText, normalizedQuery) * 0.8f);
        }

        float termScore = 0f;
        int covered = 0;
        for (String term : terms) {
            if (!StringUtils.hasText(term)) continue;
            boolean hit = false;
            if (title.contains(term)) {
                termScore += 5.5f;
                hit = true;
            }
            if (path.contains(term)) {
                termScore += 4.2f;
                hit = true;
            }
            if (summary.contains(term)) {
                termScore += 2.8f;
                hit = true;
            }
            if (content.contains(term)) {
                termScore += 2.2f;
                hit = true;
            } else if (searchText.contains(term)) {
                termScore += 1.6f;
                hit = true;
            }
            if (hit) covered++;
        }
        float coverage = terms.isEmpty() ? 0f : covered / (float) terms.size();
        termScore += coverage * 8f;

        float semanticScore = 0f;
        if (semanticHit != null) {
            semanticScore = 5f + Math.min(1f, Math.max(0f, semanticHit.score())) * 10f;
        }

        float score = exactScore + termScore + semanticScore + recencyScore(note.getUpdatedAt());
        String source;
        if (exactScore >= 16f && semanticHit != null) source = "hybrid-strong";
        else if (exactScore >= 16f || termScore >= 12f) source = "hybrid-exact";
        else if (semanticHit != null && semanticScore >= 8f) source = "hybrid-semantic";
        else source = "hybrid";
        return new HybridScore(score, source);
    }

    static float keywordScore(Note note, String query) {
        String normalizedQuery = normalize(query);
        if (!StringUtils.hasText(normalizedQuery)) return 1f;

        float score = 3f;
        String title = normalize(note.getTitle());
        String summary = normalize(note.getSummary());
        String searchText = normalize(note.getSearchText());
        String content = normalize(note.getContent());

        if (title.equals(normalizedQuery)) score += 6f;
        else if (title.contains(normalizedQuery)) score += 4f;
        if (summary.contains(normalizedQuery)) score += 3f;
        if (content.contains(normalizedQuery)) score += 2.5f;
        else if (searchText.contains(normalizedQuery)) score += 2f;
        for (String term : queryTerms(normalizedQuery)) {
            if (term.equals(normalizedQuery)) continue;
            if (title.contains(term)) score += 1.4f;
            if (summary.contains(term)) score += 0.9f;
            if (content.contains(term) || searchText.contains(term)) score += 0.6f;
        }
        score += Math.min(2f, occurrenceCount(searchText, normalizedQuery) * 0.25f);
        return score;
    }

    static String hybridSnippet(Note note, String query, List<String> terms, SearchHit semanticHit) {
        String exact = keywordSnippet(note, query);
        if (StringUtils.hasText(exact)) return exact;
        for (String term : terms) {
            String snippet = keywordSnippet(note, term);
            if (StringUtils.hasText(snippet)) return snippet;
        }
        if (semanticHit != null && StringUtils.hasText(semanticHit.snippet())) {
            return semanticHit.snippet();
        }
        return snippet(firstText(note.getSummary(), note.getContent(), note.getSearchText()));
    }

    static String keywordSnippet(Note note, String query) {
        String content = firstText(note.getContent(), note.getSummary(), note.getSearchText());
        String snippet = snippetAround(content, query);
        if (StringUtils.hasText(snippet)) return snippet;
        return snippet(firstText(note.getSummary(), note.getContent(), note.getSearchText()));
    }

    static String snippetAround(String text, String query) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(query)) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase();
        String needle = query.trim().toLowerCase();
        int index = lower.indexOf(needle);
        if (index < 0) return "";
        int start = Math.max(0, index - 70);
        int end = Math.min(cleaned.length(), index + query.trim().length() + 90);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < cleaned.length() ? "..." : "";
        return prefix + cleaned.substring(start, end) + suffix;
    }

    static List<SearchMatchExcerpt> matchExcerpts(String field, String label, String text, String query, int max) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(query) || max <= 0) return List.of();
        String cleaned = text.replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase();
        String needle = query.trim().toLowerCase();
        if (!StringUtils.hasText(needle)) return List.of();

        List<SearchMatchExcerpt> excerpts = new ArrayList<>();
        int from = 0;
        while (excerpts.size() < max) {
            int index = lower.indexOf(needle, from);
            if (index < 0) break;
            int start = Math.max(0, index - EXCERPT_RADIUS);
            int end = Math.min(cleaned.length(), index + query.trim().length() + EXCERPT_RADIUS);
            String excerpt = (start > 0 ? "..." : "") + cleaned.substring(start, end) + (end < cleaned.length() ? "..." : "");
            int matchStart = (start > 0 ? 3 : 0) + (index - start);
            excerpts.add(new SearchMatchExcerpt(field, label, excerpt, matchStart, matchStart + query.trim().length()));
            from = index + needle.length();
        }
        return excerpts;
    }

    static int countMatches(String text, String query) {
        return occurrenceCount(normalize(text), normalize(query));
    }

    static int occurrenceCount(String text, String query) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(query)) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(query, from);
            if (index < 0) return count;
            count++;
            from = index + query.length();
        }
    }

    static float rankBonus(int index, int size) {
        if (size <= 0) return 0f;
        return (size - index) / (float) size;
    }

    static float recencyScore(LocalDateTime updatedAt) {
        if (updatedAt == null) return 0f;
        long days = Math.max(0, ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now()));
        if (days <= 7) return 1.2f;
        if (days <= 30) return 0.8f;
        if (days <= 120) return 0.35f;
        return 0f;
    }

    static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    static List<String> queryTerms(String normalizedQuery) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String part : normalizedQuery.split("[\\s,，、/]+")) {
            if (StringUtils.hasText(part)) terms.add(part.trim());
        }
        if (terms.isEmpty()) terms.add(normalizedQuery);
        if (!terms.contains(normalizedQuery)) terms.add(normalizedQuery);
        return new ArrayList<>(terms);
    }

    static String notebookPath(Map<Long, Notebook> notebooks, Long notebookId) {
        List<String> names = new ArrayList<>();
        Notebook cursor = notebooks.get(notebookId);
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            names.add(0, cursor.getName());
            cursor = cursor.getParentId() == null ? null : notebooks.get(cursor.getParentId());
        }
        return String.join("/", names);
    }

    static List<String> splitPath(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        List<String> parts = new ArrayList<>();
        for (String part : value.replace("\\", "/").split("/")) {
            String clean = part.trim();
            if (StringUtils.hasText(clean)) parts.add(clean);
        }
        return parts;
    }

    static String mergeReasons(String... values) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            for (String part : value.split("；")) {
                if (StringUtils.hasText(part)) reasons.add(part.trim());
            }
        }
        return reasons.stream().limit(4).reduce((a, b) -> a + "；" + b).orElse("");
    }

    static String snippet(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "..." : cleaned;
    }

    record HybridScore(float value, String source) {
    }
}
