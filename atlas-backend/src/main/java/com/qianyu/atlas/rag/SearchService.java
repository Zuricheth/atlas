package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SearchService {
    private static final float KEYWORD_HIT_BONUS = 0.15f;
    private static final int SEMANTIC_CANDIDATE_FACTOR = 3;

    private final KeywordSearchService keywordSearchService;
    private final SearchMatchService searchMatchService;
    private final SearchTreeService searchTreeService;
    private final SemanticRetrievalService semanticRetrievalService;
    private final NotebookMapper notebookMapper;
    private final NoteMapper noteMapper;

    public SearchService(KeywordSearchService keywordSearchService,
                         SearchMatchService searchMatchService,
                         SearchTreeService searchTreeService,
                         SemanticRetrievalService semanticRetrievalService,
                         NotebookMapper notebookMapper,
                         NoteMapper noteMapper) {
        this.keywordSearchService = keywordSearchService;
        this.searchMatchService = searchMatchService;
        this.searchTreeService = searchTreeService;
        this.semanticRetrievalService = semanticRetrievalService;
        this.notebookMapper = notebookMapper;
        this.noteMapper = noteMapper;
    }

    public List<SearchHit> semantic(Long userId, String query, Integer topK) {
        return keywordSearchService.semantic(userId, query, topK);
    }

    public List<SearchHit> hybrid(Long userId, String query, Integer topK) {
        return keywordSearchService.hybrid(userId, query, topK);
    }

    public List<SearchHit> keyword(Long userId, String query, Integer topK) {
        return keywordSearchService.keyword(userId, query, topK);
    }

    public List<SearchMatchDetail> matchDetails(Long userId, String query, Integer limitValue) {
        return searchMatchService.matchDetails(userId, query, limitValue);
    }

    public SearchTreeDtos.SearchTreeResponse tree(Long userId, String query, Integer limitValue) {
        return searchTreeService.tree(userId, query, limitValue);
    }

    public List<VectorSearchResult> retrieveChunks(Long userId, String query, Integer topK) {
        return semanticRetrievalService.retrieveChunks(userId, query, SearchTextSupport.topK(topK));
    }

    /**
     * Hybrid chunk 召回: 语义召回 chunk 候选 + keyword 命中笔记加权 + 可选 notebook 子树 scope。
     * 返回加权后 score 降序的 topK 个 chunk。
     */
    public List<VectorSearchResult> retrieveHybridChunks(Long userId, String query, Integer topK, Long notebookId) {
        int limit = SearchTextSupport.topK(topK);
        if (query == null || query.isBlank()) return List.of();

        // 1. 扩大候选语义召回 chunk
        int candidateLimit = Math.max(limit * SEMANTIC_CANDIDATE_FACTOR, limit + 1);
        List<VectorSearchResult> candidates = new ArrayList<>(
                semanticRetrievalService.retrieveChunks(userId, query, candidateLimit));

        // 2. keyword 命中笔记集合(笔记级 hybrid)
        Set<Long> keywordHitNoteIds;
        try {
            keywordHitNoteIds = keywordSearchService.hybrid(userId, query, Math.max(limit * 3, 30))
                    .stream()
                    .map(SearchHit::noteId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (RuntimeException exception) {
            // keyword 失败不应阻断语义召回
            keywordHitNoteIds = Set.of();
        }

        // 3. 可选 notebook 子树 scope 过滤: 用候选 chunk 的 noteId 反查 note 的 notebookId, 只保留子树内笔记
        if (notebookId != null) {
            Set<Long> subtree = descendantNotebookIds(userId, notebookId);
            if (subtree.isEmpty()) return List.of();
            Set<Long> candidateNoteIds = candidates.stream()
                    .map(VectorSearchResult::noteId)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> scopedNoteIds = noteNotebookInSubtree(userId, candidateNoteIds, subtree);
            candidates = candidates.stream()
                    .filter(c -> scopedNoteIds.contains(c.noteId()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // 4. 加权重排: keyword 命中笔记的 chunk score +0.15
        List<VectorSearchResult> weighted = new ArrayList<>(candidates.size());
        for (VectorSearchResult chunk : candidates) {
            float base = chunk.score() == 0f ? 0f : chunk.score();
            float weightedScore = keywordHitNoteIds.contains(chunk.noteId())
                    ? base + KEYWORD_HIT_BONUS
                    : base;
            weighted.add(new VectorSearchResult(
                    chunk.chunkId(),
                    chunk.noteId(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    weightedScore));
        }
        weighted.sort(Comparator.comparingDouble(VectorSearchResult::score).reversed());
        if (weighted.size() > limit) weighted = weighted.subList(0, limit);
        return weighted;
    }

    /**
     * 从候选 noteId 中筛选出 notebookId 落在子树集合内的笔记 id。
     */
    private Set<Long> noteNotebookInSubtree(Long userId, Set<Long> noteIds, Set<Long> subtree) {
        if (noteIds.isEmpty()) return Set.of();
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getDeleted, 0)
                .in(Note::getId, noteIds));
        Set<Long> result = new HashSet<>();
        for (Note note : notes) {
            if (note.getNotebookId() != null && subtree.contains(note.getNotebookId())) {
                result.add(note.getId());
            }
        }
        return result;
    }

    /**
     * 查询 notebook 子树 id 集合(含自身), 在 SearchService 内直接用 NotebookMapper 避免循环依赖。
     */
    private Set<Long> descendantNotebookIds(Long userId, Long notebookId) {
        Set<Long> ids = new HashSet<>();
        if (notebookId == null) return ids;
        Notebook root = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        if (root == null) return ids;
        List<Long> frontier = new ArrayList<>();
        frontier.add(notebookId);
        ids.add(notebookId);
        while (!frontier.isEmpty()) {
            List<Long> current = frontier;
            frontier = new ArrayList<>();
            List<Notebook> children = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .in(Notebook::getParentId, current)
                    .eq(Notebook::getDeleted, 0));
            for (Notebook child : children) {
                if (ids.add(child.getId())) frontier.add(child.getId());
            }
        }
        return ids;
    }
}
