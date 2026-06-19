package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SearchTreeService {
    private static final Logger log = LoggerFactory.getLogger(SearchTreeService.class);

    private final KeywordSearchService keywordSearchService;
    private final NoteMapper noteMapper;
    private final NotebookMapper notebookMapper;
    private final LibraryItemMapper libraryItemMapper;

    public SearchTreeService(KeywordSearchService keywordSearchService,
                             NoteMapper noteMapper,
                             NotebookMapper notebookMapper,
                             LibraryItemMapper libraryItemMapper) {
        this.keywordSearchService = keywordSearchService;
        this.noteMapper = noteMapper;
        this.notebookMapper = notebookMapper;
        this.libraryItemMapper = libraryItemMapper;
    }

    public SearchTreeDtos.SearchTreeResponse tree(Long userId, String query, Integer limitValue) {
        int limit = SearchTextSupport.treeLimit(limitValue);
        String normalizedQuery = SearchTextSupport.normalize(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return new SearchTreeDtos.SearchTreeResponse(query, List.of(), List.of());
        }

        List<String> terms = SearchTextSupport.queryTerms(normalizedQuery);
        Map<Long, Notebook> notebookById = notebooks(userId);
        Map<Long, Float> exactNoteScores = exactNoteScores(userId, query, limit);
        Map<Long, Float> semanticNoteScores = semanticNoteScores(userId, query, limit);

        List<TreeMatchedItem> matched = new ArrayList<>();
        for (LibraryItem item : libraryItems(userId)) {
            String notebookPath = SearchTextSupport.notebookPath(notebookById, item.getNotebookId());
            String category = SearchTextSupport.firstText(item.getCategory(), "");
            TreeMatch match = treeMatch(notebookPath, category, item, terms);
            float exactScore = item.getNoteId() == null ? 0f : exactNoteScores.getOrDefault(item.getNoteId(), 0f);
            float semanticScore = item.getNoteId() == null ? 0f : semanticNoteScores.getOrDefault(item.getNoteId(), 0f);
            float totalScore = match.score() + exactScore + semanticScore;
            if (totalScore <= 0f) continue;
            String reason = SearchTextSupport.mergeReasons(
                    match.reason(),
                    exactScore > 0f ? "正文命中" : "",
                    semanticScore > 0f ? "语义命中" : ""
            );
            matched.add(new TreeMatchedItem(item, notebookPath, reason, totalScore));
        }

        matched.sort(Comparator.comparingDouble(TreeMatchedItem::score).reversed());
        if (matched.size() > limit) matched = matched.subList(0, limit);

        TreeBuilder builder = new TreeBuilder();
        Set<String> expanded = new LinkedHashSet<>();
        for (TreeMatchedItem match : matched) {
            LibraryItem item = match.item();
            List<String> parts = new ArrayList<>();
            parts.addAll(SearchTextSupport.splitPath(match.notebookPath()));
            parts.addAll(SearchTextSupport.splitPath(item.getCategory()));
            List<String> accum = new ArrayList<>();
            for (String part : parts) {
                accum.add(part);
                expanded.add(String.join("/", accum));
            }
            builder.add(parts, new SearchTreeDtos.SearchTreeNode(
                    "item:" + item.getId(),
                    StringUtils.hasText(item.getTitle()) ? item.getTitle() : item.getOriginalFilename(),
                    "library",
                    match.reason(),
                    item.getNoteId(),
                    item.getId(),
                    "/api/library/" + item.getId() + "/file",
                    List.of()
            ));
        }

        return new SearchTreeDtos.SearchTreeResponse(query, builder.roots(), new ArrayList<>(expanded));
    }

    private Map<Long, Notebook> notebooks(Long userId) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0));
        Map<Long, Notebook> notebookById = new HashMap<>();
        for (Notebook notebook : notebooks) notebookById.put(notebook.getId(), notebook);
        return notebookById;
    }

    private List<LibraryItem> libraryItems(Long userId) {
        return libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .select(
                        LibraryItem::getId,
                        LibraryItem::getUserId,
                        LibraryItem::getNotebookId,
                        LibraryItem::getNoteId,
                        LibraryItem::getTitle,
                        LibraryItem::getOriginalFilename,
                        LibraryItem::getContentType,
                        LibraryItem::getFileExt,
                        LibraryItem::getCategory,
                        LibraryItem::getUpdatedAt,
                        LibraryItem::getDeleted
                )
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getDeleted, 0)
                .orderByDesc(LibraryItem::getUpdatedAt)
                .last("limit 500"));
    }

    private TreeMatch treeMatch(String notebookPath, String category, LibraryItem item, List<String> terms) {
        float score = 0f;
        List<String> reasons = new ArrayList<>();
        String pathText = SearchTextSupport.normalize(notebookPath + "/" + category);
        String titleText = SearchTextSupport.normalize(
                SearchTextSupport.firstText(item.getTitle(), "")
                        + " "
                        + SearchTextSupport.firstText(item.getOriginalFilename(), "")
        );
        String bodyText = SearchTextSupport.normalize(SearchTextSupport.firstText(item.getExtractedText(), ""));

        for (String term : terms) {
            if (!StringUtils.hasText(term)) continue;
            if (pathText.contains(term)) {
                score += 8f;
                reasons.add("路径命中：" + term);
            }
            if (titleText.contains(term)) {
                score += 5f;
                reasons.add("标题/文件名命中：" + term);
            }
            if (bodyText.contains(term)) {
                score += 2f;
                reasons.add("正文命中：" + term);
            }
        }
        return new TreeMatch(score, reasons.isEmpty() ? "" : reasons.stream().distinct().limit(3).reduce((a, b) -> a + "；" + b).orElse(""));
    }

    private Map<Long, Float> exactNoteScores(Long userId, String query, int limit) {
        List<Note> notes = noteMapper.search(userId, query, Math.max(limit, 80));
        Map<Long, Float> scores = new HashMap<>();
        for (int i = 0; i < notes.size(); i++) {
            scores.put(notes.get(i).getId(), 4f + SearchTextSupport.rankBonus(i, notes.size()) * 2f);
        }
        return scores;
    }

    private Map<Long, Float> semanticNoteScores(Long userId, String query, int limit) {
        try {
            List<SearchHit> hits = keywordSearchService.semantic(userId, query, Math.min(Math.max(limit, 20), 80));
            Map<Long, Float> scores = new HashMap<>();
            for (int i = 0; i < hits.size(); i++) {
                SearchHit hit = hits.get(i);
                scores.put(hit.noteId(), 2f + Math.max(0f, hit.score()) * 4f + SearchTextSupport.rankBonus(i, hits.size()));
            }
            return scores;
        } catch (Exception exception) {
            log.warn("[SearchTree] semantic tree recall skipped: {}", exception.getMessage());
            return Map.of();
        }
    }

    private record TreeMatch(float score, String reason) {
    }

    private record TreeMatchedItem(LibraryItem item, String notebookPath, String reason, float score) {
    }

    private static class TreeBuilder {
        private final LinkedHashMap<String, MutableTreeNode> roots = new LinkedHashMap<>();

        void add(List<String> path, SearchTreeDtos.SearchTreeNode leaf) {
            LinkedHashMap<String, MutableTreeNode> level = roots;
            String keyPath = "";
            for (String part : path) {
                keyPath = keyPath.isBlank() ? part : keyPath + "/" + part;
                MutableTreeNode node = level.computeIfAbsent(keyPath, key -> new MutableTreeNode(
                        key,
                        part,
                        "folder",
                        "",
                        null,
                        null,
                        null
                ));
                level = node.children;
            }
            level.putIfAbsent(leaf.key(), MutableTreeNode.fromLeaf(leaf));
        }

        List<SearchTreeDtos.SearchTreeNode> roots() {
            return roots.values().stream().map(MutableTreeNode::toDto).toList();
        }
    }

    private static class MutableTreeNode {
        private final String key;
        private final String name;
        private final String type;
        private final String reason;
        private final Long noteId;
        private final Long itemId;
        private final String fileUrl;
        private final LinkedHashMap<String, MutableTreeNode> children = new LinkedHashMap<>();

        MutableTreeNode(String key, String name, String type, String reason, Long noteId, Long itemId, String fileUrl) {
            this.key = key;
            this.name = name;
            this.type = type;
            this.reason = reason;
            this.noteId = noteId;
            this.itemId = itemId;
            this.fileUrl = fileUrl;
        }

        static MutableTreeNode fromLeaf(SearchTreeDtos.SearchTreeNode leaf) {
            return new MutableTreeNode(leaf.key(), leaf.name(), leaf.type(), leaf.reason(), leaf.noteId(), leaf.itemId(), leaf.fileUrl());
        }

        SearchTreeDtos.SearchTreeNode toDto() {
            return new SearchTreeDtos.SearchTreeNode(
                    key,
                    name,
                    type,
                    reason,
                    noteId,
                    itemId,
                    fileUrl,
                    children.values().stream().map(MutableTreeNode::toDto).toList()
            );
        }
    }
}
