package com.qianyu.atlas.asset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qianyu.atlas.asset.AssetDtos.FileTypeStat;
import com.qianyu.atlas.asset.AssetDtos.AssetItem;
import com.qianyu.atlas.asset.AssetDtos.BulkAssetResponse;
import com.qianyu.atlas.asset.AssetDtos.SpaceBucket;
import com.qianyu.atlas.asset.AssetDtos.SpaceSummary;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.deepwiki.DeepWikiPage;
import com.qianyu.atlas.deepwiki.DeepWikiPageMapper;
import com.qianyu.atlas.inbox.InboxFile;
import com.qianyu.atlas.inbox.InboxFileMapper;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.paper.PaperAttachment;
import com.qianyu.atlas.paper.PaperAttachmentMapper;
import com.qianyu.atlas.rag.NoteChunk;
import com.qianyu.atlas.rag.NoteChunkMapper;
import com.qianyu.atlas.vcp.VcpMemoryDraft;
import com.qianyu.atlas.vcp.VcpMemoryDraftMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class AssetService {
    private final LibraryItemMapper libraryItemMapper;
    private final PaperAttachmentMapper paperAttachmentMapper;
    private final NoteMapper noteMapper;
    private final NoteChunkMapper noteChunkMapper;
    private final DeepWikiPageMapper deepWikiPageMapper;
    private final VcpMemoryDraftMapper vcpMemoryDraftMapper;
    private final InboxFileMapper inboxFileMapper;
    private final NotebookMapper notebookMapper;

    public AssetService(LibraryItemMapper libraryItemMapper,
                        PaperAttachmentMapper paperAttachmentMapper,
                        NoteMapper noteMapper,
                        NoteChunkMapper noteChunkMapper,
                        DeepWikiPageMapper deepWikiPageMapper,
                        VcpMemoryDraftMapper vcpMemoryDraftMapper,
                        InboxFileMapper inboxFileMapper,
                        NotebookMapper notebookMapper) {
        this.libraryItemMapper = libraryItemMapper;
        this.paperAttachmentMapper = paperAttachmentMapper;
        this.noteMapper = noteMapper;
        this.noteChunkMapper = noteChunkMapper;
        this.deepWikiPageMapper = deepWikiPageMapper;
        this.vcpMemoryDraftMapper = vcpMemoryDraftMapper;
        this.inboxFileMapper = inboxFileMapper;
        this.notebookMapper = notebookMapper;
    }

    public SpaceSummary summary(Long userId) {
        List<LibraryItem> libraryItems = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId));
        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId));
        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId));
        List<NoteChunk> chunks = noteChunkMapper.selectList(new LambdaQueryWrapper<NoteChunk>()
                .eq(NoteChunk::getUserId, userId));
        List<DeepWikiPage> pages = deepWikiPageMapper.selectList(new LambdaQueryWrapper<DeepWikiPage>()
                .eq(DeepWikiPage::getUserId, userId));
        List<VcpMemoryDraft> drafts = vcpMemoryDraftMapper.selectList(new LambdaQueryWrapper<VcpMemoryDraft>()
                .eq(VcpMemoryDraft::getUserId, userId));
        List<InboxFile> inboxFiles = inboxFileMapper.selectList(new LambdaQueryWrapper<InboxFile>()
                .eq(InboxFile::getUserId, userId));

        long activeLibraryBytes = libraryItems.stream().filter(item -> isActive(item.getDeleted())).mapToLong(item -> safeLong(item.getFileSize())).sum();
        long activePaperBytes = papers.stream().filter(paper -> isActive(paper.getDeleted())).mapToLong(paper -> safeLong(paper.getFileSize())).sum();
        long activeOriginalBytes = activeLibraryBytes + activePaperBytes;
        long activeFiles = libraryItems.stream().filter(item -> isActive(item.getDeleted())).count()
                + papers.stream().filter(paper -> isActive(paper.getDeleted())).count();

        long extractedBytes = libraryItems.stream().filter(item -> isActive(item.getDeleted())).mapToLong(item -> utf8Bytes(item.getExtractedText())).sum()
                + papers.stream().filter(paper -> isActive(paper.getDeleted())).mapToLong(paper -> utf8Bytes(paper.getExtractedText())).sum();
        long noteBytes = notes.stream().filter(note -> isActive(note.getDeleted()))
                .mapToLong(note -> utf8Bytes(note.getTitle()) + utf8Bytes(note.getSummary()) + utf8Bytes(note.getContent()) + utf8Bytes(note.getSearchText()))
                .sum();
        long vectorBytes = chunks.stream().mapToLong(chunk -> utf8Bytes(chunk.getContent()) + utf8Bytes(chunk.getEmbedding())).sum();
        long deepWikiBytes = pages.stream().mapToLong(page -> utf8Bytes(page.getTitle()) + utf8Bytes(page.getMarkdown())).sum();
        long draftBytes = drafts.stream().mapToLong(draft -> utf8Bytes(draft.getTitle()) + utf8Bytes(draft.getMemoryContent())).sum();

        long inboxBytes = inboxFiles.stream()
                .filter(file -> !InboxFile.STATUS_IMPORTED.equals(file.getStatus()))
                .mapToLong(file -> safeLong(file.getFileSize()))
                .sum();
        long importedInboxBytes = inboxFiles.stream()
                .filter(file -> InboxFile.STATUS_IMPORTED.equals(file.getStatus()) || InboxFile.STATUS_SKIPPED.equals(file.getStatus()))
                .mapToLong(file -> safeLong(file.getFileSize()))
                .sum();
        long trashOriginalBytes = libraryItems.stream().filter(item -> !isActive(item.getDeleted())).mapToLong(item -> safeLong(item.getFileSize())).sum()
                + papers.stream().filter(paper -> !isActive(paper.getDeleted())).mapToLong(paper -> safeLong(paper.getFileSize())).sum();
        long trashNoteBytes = notes.stream().filter(note -> !isActive(note.getDeleted()))
                .mapToLong(note -> utf8Bytes(note.getTitle()) + utf8Bytes(note.getSummary()) + utf8Bytes(note.getContent()) + utf8Bytes(note.getSearchText()))
                .sum();
        long trashBytes = trashOriginalBytes + trashNoteBytes;
        long trashItems = libraryItems.stream().filter(item -> !isActive(item.getDeleted())).count()
                + papers.stream().filter(paper -> !isActive(paper.getDeleted())).count()
                + notes.stream().filter(note -> !isActive(note.getDeleted())).count();

        List<SpaceBucket> buckets = new ArrayList<>();
        buckets.add(new SpaceBucket("original", "原文件", activeFiles, activeOriginalBytes, false, "Atlas 管理的资料、图片、PDF 原件。"));
        buckets.add(new SpaceBucket("extracted", "提取文本", activeFiles, extractedBytes, false, "从 txt、md、html、pdf 中抽取给 AI 和检索使用的文本。"));
        buckets.add(new SpaceBucket("notes", "Atlas 笔记", notes.stream().filter(note -> isActive(note.getDeleted())).count(), noteBytes, false, "给人阅读的 Markdown/HTML 笔记与搜索文本。"));
        buckets.add(new SpaceBucket("vectors", "向量索引", chunks.size(), vectorBytes, false, "RAG 召回用的分块文本和 embedding 数据。"));
        buckets.add(new SpaceBucket("deepwiki", "DeepWiki 页面", pages.size(), deepWikiBytes, true, "可重新生成的 DeepWiki 缓存页面。"));
        buckets.add(new SpaceBucket("vcp", "VCP 草稿", drafts.size(), draftBytes, true, "待审核或已同步的 AI 记忆草稿。"));
        buckets.add(new SpaceBucket("inbox", "投递箱临时文件", inboxFiles.size(), inboxBytes + importedInboxBytes, true, "外部项目投递过来的临时副本。"));
        buckets.add(new SpaceBucket("trash", "回收站", trashItems, trashBytes, true, "已删除但仍在保留期内的项目。"));

        long totalBytes = buckets.stream().mapToLong(SpaceBucket::bytes).sum();
        long reclaimableBytes = deepWikiBytes + draftBytes + importedInboxBytes + trashBytes;
        long imageFiles = libraryItems.stream().filter(item -> isActive(item.getDeleted()) && isImage(item)).count();

        return new SpaceSummary(
                totalBytes,
                reclaimableBytes,
                activeFiles,
                trashItems,
                imageFiles,
                buckets,
                fileTypes(libraryItems, papers)
        );
    }

    public List<AssetItem> items(Long userId, Long notebookId, String type, String query, int limit) {
        int safeLimit = Math.max(20, Math.min(limit <= 0 ? 240 : limit, 1000));
        String normalizedType = type == null ? "" : type.trim();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<Long, Notebook> notebookCache = new HashMap<>();
        List<AssetItem> result = new ArrayList<>();

        List<LibraryItem> libraryItems = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getDeleted, 0)
                .eq(notebookId != null, LibraryItem::getNotebookId, notebookId)
                .orderByDesc(LibraryItem::getUpdatedAt)
                .orderByDesc(LibraryItem::getId));
        for (LibraryItem item : libraryItems) {
            String label = typeLabel(item.getContentType(), item.getFileExt(), item.getOriginalFilename());
            AssetItem view = libraryAsset(userId, item, label, notebookCache);
            if (assetMatches(view, normalizedType, normalizedQuery)) result.add(view);
            if (result.size() >= safeLimit) return result;
        }

        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getDeleted, 0)
                .eq(notebookId != null, PaperAttachment::getNotebookId, notebookId)
                .orderByDesc(PaperAttachment::getUpdatedAt)
                .orderByDesc(PaperAttachment::getId));
        for (PaperAttachment paper : papers) {
            AssetItem view = paperAsset(userId, paper, notebookCache);
            if (assetMatches(view, normalizedType, normalizedQuery)) result.add(view);
            if (result.size() >= safeLimit) return result;
        }

        result.sort(Comparator.comparing(AssetItem::updatedAt, Comparator.nullsLast(String::compareTo)).reversed());
        return result.size() > safeLimit ? result.subList(0, safeLimit) : result;
    }

    @Transactional
    public BulkAssetResponse trash(Long userId, List<String> keys) {
        SelectedAssets selected = requireSelectedAssets(userId, keys);
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        if (!selected.libraryItems().isEmpty()) {
            changed += libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                    .set(LibraryItem::getDeleted, 1)
                    .set(LibraryItem::getUpdatedAt, now)
                    .eq(LibraryItem::getUserId, userId)
                    .eq(LibraryItem::getDeleted, 0)
                    .in(LibraryItem::getId, selected.libraryItems().stream().map(LibraryItem::getId).toList()));
        }
        if (!selected.papers().isEmpty()) {
            changed += paperAttachmentMapper.update(null, new LambdaUpdateWrapper<PaperAttachment>()
                    .set(PaperAttachment::getDeleted, 1)
                    .set(PaperAttachment::getUpdatedAt, now)
                    .eq(PaperAttachment::getUserId, userId)
                    .eq(PaperAttachment::getDeleted, 0)
                    .in(PaperAttachment::getId, selected.papers().stream().map(PaperAttachment::getId).toList()));
        }
        if (changed != selected.count()) {
            throw new BizException("部分资产状态已变化，请刷新后重试");
        }
        return new BulkAssetResponse(changed);
    }

    public void writeArchive(Long userId, List<String> keys, OutputStream outputStream) {
        SelectedAssets selected = requireSelectedAssets(userId, keys);
        Set<String> usedEntries = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (SelectedFile file : selected.files()) {
                Path path = storagePath(file.storagePath());
                if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
                    missing.add(file.filename());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(uniqueArchiveName(file.filename(), usedEntries)));
                Files.copy(path, zip);
                zip.closeEntry();
            }
            if (!missing.isEmpty()) {
                zip.putNextEntry(new ZipEntry("_导出说明/缺失文件.txt"));
                zip.write(("以下文件在数据库中有记录，但磁盘原文件不存在，已跳过：\n\n"
                        + String.join("\n", missing)).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("资产导出失败", exception);
        }
    }

    private SelectedAssets requireSelectedAssets(Long userId, List<String> keys) {
        List<AssetKey> parsed = parseKeys(keys);
        List<Long> libraryIds = parsed.stream().filter(key -> "library".equals(key.source())).map(AssetKey::id).toList();
        List<Long> paperIds = parsed.stream().filter(key -> "paper".equals(key.source())).map(AssetKey::id).toList();

        List<LibraryItem> libraryItems = libraryIds.isEmpty() ? List.of() : libraryItemMapper.selectList(
                new LambdaQueryWrapper<LibraryItem>()
                        .eq(LibraryItem::getUserId, userId)
                        .eq(LibraryItem::getDeleted, 0)
                        .in(LibraryItem::getId, libraryIds));
        List<PaperAttachment> papers = paperIds.isEmpty() ? List.of() : paperAttachmentMapper.selectList(
                new LambdaQueryWrapper<PaperAttachment>()
                        .eq(PaperAttachment::getUserId, userId)
                        .eq(PaperAttachment::getDeleted, 0)
                        .in(PaperAttachment::getId, paperIds));

        Map<String, LibraryItem> libraryByKey = new HashMap<>();
        for (LibraryItem item : libraryItems) libraryByKey.put("library:" + item.getId(), item);
        Map<String, PaperAttachment> paperByKey = new HashMap<>();
        for (PaperAttachment paper : papers) paperByKey.put("paper:" + paper.getId(), paper);

        List<LibraryItem> orderedLibrary = new ArrayList<>();
        List<PaperAttachment> orderedPapers = new ArrayList<>();
        List<SelectedFile> files = new ArrayList<>();
        for (AssetKey key : parsed) {
            if ("library".equals(key.source())) {
                LibraryItem item = libraryByKey.get(key.value());
                if (item == null) throw new BizException(404, "部分资产不存在或已移入回收站");
                orderedLibrary.add(item);
                files.add(new SelectedFile(item.getOriginalFilename(), item.getStoragePath()));
            } else {
                PaperAttachment paper = paperByKey.get(key.value());
                if (paper == null) throw new BizException(404, "部分资产不存在或已移入回收站");
                orderedPapers.add(paper);
                files.add(new SelectedFile(paper.getOriginalFilename(), paper.getStoragePath()));
            }
        }
        return new SelectedAssets(orderedLibrary, orderedPapers, files);
    }

    private List<AssetKey> parseKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) throw new BizException("请至少选择一个资产");
        if (keys.size() > 500) throw new BizException("单次最多处理 500 个资产");
        Map<String, AssetKey> parsed = new LinkedHashMap<>();
        for (String raw : keys) {
            String value = raw == null ? "" : raw.trim();
            String[] parts = value.split(":", 2);
            if (parts.length != 2 || (!"library".equals(parts[0]) && !"paper".equals(parts[0]))) {
                throw new BizException("资产键格式无效");
            }
            try {
                long id = Long.parseLong(parts[1]);
                if (id <= 0) throw new NumberFormatException();
                parsed.putIfAbsent(value, new AssetKey(value, parts[0], id));
            } catch (NumberFormatException exception) {
                throw new BizException("资产键格式无效");
            }
        }
        return new ArrayList<>(parsed.values());
    }

    private Path storagePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception exception) {
            log.debug("[Asset] invalid storage path skipped: {}", value, exception);
            return null;
        }
    }

    private String uniqueArchiveName(String filename, Set<String> used) {
        String safe = safeArchiveName(filename);
        if (used.add(safe)) return safe;
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        int suffix = 2;
        String candidate;
        do {
            candidate = base + " (" + suffix++ + ")" + extension;
        } while (!used.add(candidate));
        return candidate;
    }

    private String safeArchiveName(String filename) {
        String safe = filename == null ? "" : filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return safe.isEmpty() ? "未命名资产" : safe;
    }

    private AssetItem libraryAsset(Long userId, LibraryItem item, String typeLabel, Map<Long, Notebook> notebookCache) {
        Notebook notebook = notebook(userId, item.getNotebookId(), notebookCache);
        return new AssetItem(
                "library:" + item.getId(),
                "library",
                item.getId(),
                item.getNoteId(),
                item.getNotebookId(),
                notebook == null ? "" : notebook.getName(),
                notebookPath(userId, notebook, notebookCache),
                item.getTitle(),
                item.getOriginalFilename(),
                "/api/library/" + item.getId() + "/file",
                item.getContentType(),
                item.getFileExt(),
                typeLabel,
                safeLong(item.getFileSize()),
                item.getCategory(),
                "图片".equals(typeLabel),
                item.getStatus(),
                item.getCreatedAt() == null ? "" : item.getCreatedAt().toString(),
                item.getUpdatedAt() == null ? "" : item.getUpdatedAt().toString()
        );
    }

    private AssetItem paperAsset(Long userId, PaperAttachment paper, Map<Long, Notebook> notebookCache) {
        Notebook notebook = notebook(userId, paper.getNotebookId(), notebookCache);
        return new AssetItem(
                "paper:" + paper.getId(),
                "paper",
                paper.getId(),
                paper.getNoteId(),
                paper.getNotebookId(),
                notebook == null ? "" : notebook.getName(),
                notebookPath(userId, notebook, notebookCache),
                paper.getOriginalFilename(),
                paper.getOriginalFilename(),
                "/api/papers/" + paper.getId() + "/file",
                paper.getContentType(),
                "pdf",
                "PDF",
                safeLong(paper.getFileSize()),
                "论文/PDF",
                false,
                "READY",
                paper.getCreatedAt() == null ? "" : paper.getCreatedAt().toString(),
                paper.getUpdatedAt() == null ? "" : paper.getUpdatedAt().toString()
        );
    }

    private boolean assetMatches(AssetItem item, String type, String query) {
        if (item == null) return false;
        if (!type.isBlank() && !"全部".equals(type) && !type.equals(item.typeLabel())) return false;
        if (query.isBlank()) return true;
        String haystack = String.join(" ",
                safeText(item.title()),
                safeText(item.originalFilename()),
                safeText(item.notebookPath()),
                safeText(item.category()),
                safeText(item.typeLabel())
        ).toLowerCase(Locale.ROOT);
        return haystack.contains(query);
    }

    private Notebook notebook(Long userId, Long notebookId, Map<Long, Notebook> cache) {
        if (notebookId == null) return null;
        if (cache.containsKey(notebookId)) return cache.get(notebookId);
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        cache.put(notebookId, notebook);
        return notebook;
    }

    private String notebookPath(Long userId, Notebook notebook, Map<Long, Notebook> cache) {
        if (notebook == null) return "";
        List<String> names = new ArrayList<>();
        Notebook cursor = notebook;
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            names.add(0, cursor.getName());
            Long parentId = cursor.getParentId();
            if (parentId == null) break;
            cursor = notebook(userId, parentId, cache);
        }
        return String.join(" / ", names);
    }

    private List<FileTypeStat> fileTypes(List<LibraryItem> libraryItems, List<PaperAttachment> papers) {
        Map<String, long[]> stats = new HashMap<>();
        for (LibraryItem item : libraryItems) {
            if (!isActive(item.getDeleted())) continue;
            String label = typeLabel(item.getContentType(), item.getFileExt(), item.getOriginalFilename());
            long[] value = stats.computeIfAbsent(label, ignored -> new long[2]);
            value[0]++;
            value[1] += safeLong(item.getFileSize());
        }
        for (PaperAttachment paper : papers) {
            if (!isActive(paper.getDeleted())) continue;
            long[] value = stats.computeIfAbsent("PDF", ignored -> new long[2]);
            value[0]++;
            value[1] += safeLong(paper.getFileSize());
        }
        return stats.entrySet().stream()
                .map(entry -> new FileTypeStat(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(FileTypeStat::bytes).reversed())
                .toList();
    }

    private String typeLabel(String contentType, String extension, String filename) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/") || name.matches(".*\\.(png|jpe?g|webp|gif|bmp|svg)$")) return "图片";
        if (type.contains("pdf") || "pdf".equals(ext)) return "PDF";
        if ("md".equals(ext) || "markdown".equals(ext)) return "Markdown";
        if ("txt".equals(ext)) return "文本";
        if ("html".equals(ext) || "htm".equals(ext)) return "HTML";
        if (List.of("js", "ts", "py", "java", "css", "json", "xml", "yml", "yaml").contains(ext)) return "代码/结构化文本";
        return String.valueOf(ext == null || ext.isBlank() ? "其他" : ext.toUpperCase(Locale.ROOT));
    }

    private boolean isImage(LibraryItem item) {
        return "图片".equals(typeLabel(item.getContentType(), item.getFileExt(), item.getOriginalFilename()));
    }

    private boolean isActive(Integer deleted) {
        return deleted == null || deleted == 0;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private long utf8Bytes(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record AssetKey(String value, String source, long id) {
    }

    private record SelectedFile(String filename, String storagePath) {
    }

    private record SelectedAssets(
            List<LibraryItem> libraryItems,
            List<PaperAttachment> papers,
            List<SelectedFile> files
    ) {
        int count() {
            return libraryItems.size() + papers.size();
        }
    }
}
