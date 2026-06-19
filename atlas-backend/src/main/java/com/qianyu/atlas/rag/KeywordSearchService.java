package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KeywordSearchService {
    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    private final SemanticRetrievalService semanticRetrievalService;
    private final NoteMapper noteMapper;
    private final NotebookMapper notebookMapper;

    public KeywordSearchService(SemanticRetrievalService semanticRetrievalService,
                                NoteMapper noteMapper,
                                NotebookMapper notebookMapper) {
        this.semanticRetrievalService = semanticRetrievalService;
        this.noteMapper = noteMapper;
        this.notebookMapper = notebookMapper;
    }

    public List<SearchHit> semantic(Long userId, String query, Integer topK) {
        int limit = SearchTextSupport.topK(topK);
        if (!StringUtils.hasText(query)) return List.of();
        return boostExactMatches(userId, query, semanticRetrievalService.semanticHits(userId, query, limit), limit);
    }

    public List<SearchHit> hybrid(Long userId, String query, Integer topK) {
        int limit = SearchTextSupport.topK(topK);
        if (!StringUtils.hasText(query)) return List.of();

        String normalizedQuery = SearchTextSupport.normalize(query);
        List<String> terms = SearchTextSupport.queryTerms(normalizedQuery);
        int candidateLimit = Math.max(limit * 6, 80);

        Map<Long, Note> candidateNotes = candidateNotes(userId, query, normalizedQuery, terms, candidateLimit, limit);
        List<SearchHit> vectorHits = semanticPart(userId, query, candidateLimit);

        Map<Long, SearchHit> semanticByNote = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            SearchHit hit = vectorHits.get(i);
            semanticByNote.put(hit.noteId(), new SearchHit(
                    hit.noteId(),
                    hit.title(),
                    hit.snippet(),
                    Math.max(0f, hit.score()) + SearchTextSupport.rankBonus(i, vectorHits.size()) * 0.2f,
                    hit.source()
            ));
        }
        loadMissingNotes(userId, candidateNotes, semanticByNote.keySet());

        Map<Long, String> notebookPaths = notebookPaths(userId);
        List<SearchHit> merged = new ArrayList<>(candidateNotes.size());
        for (Note note : candidateNotes.values()) {
            SearchHit semanticHit = semanticByNote.get(note.getId());
            SearchTextSupport.HybridScore score = SearchTextSupport.hybridScore(
                    note,
                    notebookPaths.getOrDefault(note.getNotebookId(), ""),
                    normalizedQuery,
                    terms,
                    semanticHit
            );
            if (score.value() <= 0f) continue;
            merged.add(new SearchHit(
                    note.getId(),
                    note.getTitle(),
                    SearchTextSupport.hybridSnippet(note, query, terms, semanticHit),
                    score.value(),
                    score.source()
            ));
        }
        merged.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        if (merged.size() > limit) merged = merged.subList(0, limit);
        return merged;
    }

    public List<SearchHit> keyword(Long userId, String query, Integer topK) {
        int limit = SearchTextSupport.topK(topK);
        if (!StringUtils.hasText(query)) return List.of();

        String normalizedQuery = SearchTextSupport.normalize(query);
        List<String> terms = SearchTextSupport.queryTerms(normalizedQuery);
        int candidateLimit = Math.max(limit * 6, 80);
        Map<Long, Note> candidates = candidateNotes(userId, query, normalizedQuery, terms, candidateLimit, limit);

        Map<Long, String> notebookPaths = notebookPaths(userId);
        List<SearchHit> hits = new ArrayList<>(candidates.size());
        for (Note note : candidates.values()) {
            SearchTextSupport.HybridScore score = SearchTextSupport.hybridScore(
                    note,
                    notebookPaths.getOrDefault(note.getNotebookId(), ""),
                    normalizedQuery,
                    terms,
                    null
            );
            if (score.value() <= 0f) continue;
            hits.add(new SearchHit(
                    note.getId(),
                    note.getTitle(),
                    SearchTextSupport.hybridSnippet(note, query, terms, null),
                    score.value(),
                    "keyword"
            ));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        if (hits.size() > limit) hits = hits.subList(0, limit);
        return hits;
    }

    Map<Long, String> notebookPaths(Long userId) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0));
        Map<Long, Notebook> notebookById = new HashMap<>();
        for (Notebook notebook : notebooks) notebookById.put(notebook.getId(), notebook);
        Map<Long, String> paths = new HashMap<>();
        for (Notebook notebook : notebooks) {
            paths.put(notebook.getId(), SearchTextSupport.notebookPath(notebookById, notebook.getId()));
        }
        return paths;
    }

    private Map<Long, Note> candidateNotes(Long userId,
                                           String query,
                                           String normalizedQuery,
                                           List<String> terms,
                                           int candidateLimit,
                                           int limit) {
        Map<Long, Note> candidates = new LinkedHashMap<>();
        for (Note note : noteMapper.search(userId, query, candidateLimit)) {
            candidates.put(note.getId(), note);
        }
        for (String term : terms) {
            if (term.equals(normalizedQuery)) continue;
            for (Note note : noteMapper.search(userId, term, Math.max(limit * 3, 40))) {
                candidates.putIfAbsent(note.getId(), note);
            }
        }
        return candidates;
    }

    private List<SearchHit> semanticPart(Long userId, String query, int candidateLimit) {
        try {
            return semantic(userId, query, candidateLimit);
        } catch (BizException exception) {
            log.warn("[RAG] semantic part of hybrid search failed, fallback to keyword only: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<SearchHit> boostExactMatches(Long userId, String query, List<SearchHit> semanticHits, int limit) {
        semanticHits = filterActiveHits(userId, semanticHits);
        List<Note> keywordHits = noteMapper.search(userId, query, limit);
        if (keywordHits.isEmpty()) return semanticHits;

        Map<Long, SearchHit> hits = new LinkedHashMap<>();
        Map<Long, Float> scores = new HashMap<>();

        for (int i = 0; i < keywordHits.size(); i++) {
            Note note = keywordHits.get(i);
            float score = SearchTextSupport.keywordScore(note, query)
                    + SearchTextSupport.rankBonus(i, keywordHits.size());
            hits.put(note.getId(), new SearchHit(
                    note.getId(),
                    note.getTitle(),
                    SearchTextSupport.keywordSnippet(note, query),
                    score,
                    "semantic-exact"
            ));
            scores.put(note.getId(), score);
        }

        for (SearchHit hit : semanticHits) {
            float semanticScore = Math.max(0f, hit.score());
            if (hits.containsKey(hit.noteId())) {
                scores.merge(hit.noteId(), Math.min(1f, semanticScore), Float::sum);
            } else {
                hits.put(hit.noteId(), hit);
                scores.put(hit.noteId(), semanticScore);
            }
        }

        List<SearchHit> merged = new ArrayList<>(hits.size());
        for (SearchHit hit : hits.values()) {
            merged.add(new SearchHit(
                    hit.noteId(),
                    hit.title(),
                    hit.snippet(),
                    scores.getOrDefault(hit.noteId(), hit.score()),
                    hit.source()
            ));
        }
        merged.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        if (merged.size() > limit) merged = merged.subList(0, limit);
        return merged;
    }

    private void loadMissingNotes(Long userId, Map<Long, Note> candidateNotes, Set<Long> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) return;
        Set<Long> missing = new HashSet<>();
        for (Long noteId : noteIds) {
            if (noteId != null && !candidateNotes.containsKey(noteId)) missing.add(noteId);
        }
        if (missing.isEmpty()) return;
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .in(Note::getId, missing));
        for (Note note : notes) candidateNotes.putIfAbsent(note.getId(), note);
    }

    private List<SearchHit> filterActiveHits(Long userId, List<SearchHit> hits) {
        if (hits.isEmpty()) return hits;
        Set<Long> ids = new HashSet<>();
        for (SearchHit hit : hits) {
            if (hit.noteId() != null) ids.add(hit.noteId());
        }
        Set<Long> activeIds = activeNoteIds(userId, ids);
        List<SearchHit> filtered = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (activeIds.contains(hit.noteId())) filtered.add(hit);
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
}
