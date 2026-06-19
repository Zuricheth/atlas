package com.qianyu.atlas.library;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianyu.atlas.chat.ChatClient;
import com.qianyu.atlas.chat.ChatClientFactory;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.document.ConvertedDocument;
import com.qianyu.atlas.document.DocumentConversionService;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteDtos.SaveNoteRequest;
import com.qianyu.atlas.note.NoteService;
import com.qianyu.atlas.library.LibraryDtos.FolderImportResponse;
import com.qianyu.atlas.library.LibraryDtos.FolderPlanInput;
import com.qianyu.atlas.library.LibraryDtos.FolderPlanResponse;
import com.qianyu.atlas.library.LibraryDtos.FolderPlannedFile;
import com.qianyu.atlas.library.LibraryDtos.ImportLibraryItemResponse;
import com.qianyu.atlas.library.LibraryDtos.LibraryItemView;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.notebook.NotebookService;
import com.qianyu.atlas.tag.Tag;
import com.qianyu.atlas.tag.TagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class LibraryService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_EXTRACTED_TEXT = 240_000;

    private final LibraryItemMapper libraryItemMapper;
    private final LibraryStorageProperties properties;
    private final NoteService noteService;
    private final NotebookMapper notebookMapper;
    private final NotebookService notebookService;
    private final TagService tagService;
    private final ChatClientFactory chatClientFactory;
    private final DocumentConversionService documentConversionService;
    private final com.qianyu.atlas.ai.TracedChatHelper tracedChat;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    /**
     * Self-injection: importFolder 没有外层事务, 需要通过代理调用 createLibraryItem
     * 让每个文件走独立事务 (@Transactional 自调用不生效, 必须经由代理).
     */
    private final LibraryService self;

    public LibraryService(LibraryItemMapper libraryItemMapper,
                          LibraryStorageProperties properties,
                          NoteService noteService,
                          NotebookMapper notebookMapper,
                          NotebookService notebookService,
                          TagService tagService,
                          ChatClientFactory chatClientFactory,
                          DocumentConversionService documentConversionService,
                          @Lazy @Autowired LibraryService self,
                          com.qianyu.atlas.ai.TracedChatHelper tracedChat,
                          com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.libraryItemMapper = libraryItemMapper;
        this.properties = properties;
        this.noteService = noteService;
        this.notebookMapper = notebookMapper;
        this.notebookService = notebookService;
        this.tagService = tagService;
        this.chatClientFactory = chatClientFactory;
        this.documentConversionService = documentConversionService;
        this.self = self;
        this.tracedChat = tracedChat;
        this.aiTracer = aiTracer;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportLibraryItemResponse importItem(Long userId,
                                                Long notebookId,
                                                String title,
                                                String category,
                                                MultipartFile file) {
        validateFile(file);

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        String storedFilename = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
        Path userDirectory = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId));
        Path targetPath = userDirectory.resolve(storedFilename).normalize();

        try {
            Files.createDirectories(userDirectory);
            file.transferTo(targetPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("资料文件保存失败", exception);
        }
        registerFileRollback(targetPath);

        String resolvedTitle = StringUtils.hasText(title) ? title.trim() : stripExtension(originalFilename);
        String detectedCategory = StringUtils.hasText(category) ? category.trim() : detectCategory(extension, file.getContentType(), "");
        ExtractedContent extracted = extract(userId, targetPath, originalFilename, extension, file.getContentType());

        return createLibraryItem(userId, notebookId, resolvedTitle, originalFilename, storedFilename, targetPath, file, extension, detectedCategory, extracted, List.of()).withAiTrace(aiTracer.drain());
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportLibraryItemResponse autoImport(Long userId, MultipartFile file) {
        validateFile(file);

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String extension = extensionOf(originalFilename);
        String storedFilename = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
        Path userDirectory = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId));
        Path targetPath = userDirectory.resolve(storedFilename).normalize();

        try {
            Files.createDirectories(userDirectory);
            file.transferTo(targetPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("资料文件保存失败", exception);
        }
        registerFileRollback(targetPath);

        ExtractedContent extracted = extract(userId, targetPath, originalFilename, extension, file.getContentType());
        String sample = StringUtils.hasText(extracted.text()) ? extracted.text() : originalFilename;
        String resolvedTitle = stripExtension(originalFilename);
        AutoPlan plan = planAutoImport(originalFilename, extension, file.getContentType(), sample);
        Notebook notebook = notebookService.ensureCollectionPath(
                userId,
                plan.domainName(),
                plan.projectName(),
                plan.collectionName(),
                plan.notebookDescription()
        );

        return createLibraryItem(userId, notebook.getId(), resolvedTitle, originalFilename, storedFilename, targetPath, file, extension, plan.categoryPath(), extracted, plan.tags()).withAiTrace(aiTracer.drain());
    }

    public FolderPlanResponse planFolder(Long userId, LibraryDtos.FolderPlanRequest request) {
        List<FolderPlanInput> files = request == null ? null : request.files();
        if (files == null || files.isEmpty()) {
            throw new BizException("请选择要规划的文件夹");
        }
        if (files.size() > 300) {
            throw new BizException("MVP 阶段一次文件夹规划最多 300 个文件。当前文件过多，建议先拆成几个主题文件夹");
        }

        List<FolderMeta> metas = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            FolderPlanInput file = files.get(i);
            String relativePath = sanitizeRelativePath(file == null ? null : file.path());
            String originalFilename = sanitizeFilename(filenameFromPath(relativePath));
            String extension = extensionOf(originalFilename);
            metas.add(new FolderMeta(
                    i + 1,
                    originalFilename,
                    relativePath,
                    extension,
                    file == null ? null : file.type(),
                    file == null ? null : file.size(),
                    file == null ? "" : normalizeText(file.text())
            ));
        }

        String rootName = rootNameFromPaths(metas.stream().map(FolderMeta::relativePath).toList());
        FolderPlanningResult structuralPlanning = planFolderByStructure(rootName, metas);
        String previousPlanJson = "";
        try {
            if (request != null && request.previousPlan() != null) {
                previousPlanJson = MAPPER.writeValueAsString(request.previousPlan());
            }
        } catch (Exception exception) {
            log.warn("[Library] failed to serialize previous folder plan, userId={}", userId, exception);
            previousPlanJson = "";
        }
        String correction = request == null ? "" : request.correction();
        FolderPlanningResult planning = StringUtils.hasText(correction)
                ? planFolderNamesWithAi(userId, rootName, metas, correction, previousPlanJson)
                : structuralPlanning.plans().isEmpty()
                ? planFolderNamesWithAi(userId, rootName, metas, "", "")
                : polishStructuredPlanWithAi(userId, rootName, metas, structuralPlanning);
        if (planning.plans().isEmpty() && !structuralPlanning.plans().isEmpty()) {
            planning = structuralPlanning;
        }
        Map<String, FolderFilePlan> byPath = new HashMap<>();
        for (FolderMeta meta : metas) {
            FolderFilePlan plan = planning.plans().getOrDefault(meta.id(), fallbackFolderPlan(rootName, meta));
            plan = normalizeFolderFilePlan(rootName, meta, plan);
            byPath.put(meta.relativePath(), plan);
        }
        return folderPlanResponse(userId, rootName, planning.planner(), byPath);
    }

    public FolderImportResponse importFolder(Long userId, List<MultipartFile> files, List<String> paths, String planJson) {
        if (files == null || files.isEmpty()) {
            throw new BizException("请选择要导入的文件夹");
        }
        if (files.size() > 300) {
            throw new BizException("MVP 阶段一次文件夹导入最多 300 个文件。当前文件过多，建议先拆成几个主题文件夹");
        }

        List<FolderDraft> drafts = new ArrayList<>();
        // 落盘但还未入库的文件; 出错或部分成功时, 把没建出 LibraryItem 的物理文件清掉, 防止孤儿 PDF
        List<Path> orphanCandidates = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                validateFile(file);
                String relativePath = sanitizeRelativePath(paths != null && i < paths.size() ? paths.get(i) : file.getOriginalFilename());
                String originalFilename = sanitizeFilename(filenameFromPath(relativePath));
                String extension = extensionOf(originalFilename);
                String storedFilename = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
                Path userDirectory = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId));
                Path targetPath = userDirectory.resolve(storedFilename).normalize();
                try {
                    Files.createDirectories(userDirectory);
                    file.transferTo(targetPath);
                } catch (IOException exception) {
                    throw new UncheckedIOException("资料文件保存失败", exception);
                }
                orphanCandidates.add(targetPath);
                ExtractedContent extracted = extract(userId, targetPath, originalFilename, extension, file.getContentType());
                drafts.add(new FolderDraft(i + 1, file, originalFilename, relativePath, storedFilename, targetPath, extension, extracted));
            }

            String rootName = rootName(drafts);
            FolderPlanningResult planning = StringUtils.hasText(planJson)
                    ? planningFromJson(userId, rootName, planJson, drafts)
                    : planFolderNamesWithAi(userId, rootName, drafts.stream().map(this::metaFromDraft).toList(), "", "");
            planning = refineUncertainFolderPlans(rootName, drafts, planning);
            Map<Integer, FolderFilePlan> plans = planning.plans();
            String planner = planning.planner();

            List<ImportLibraryItemResponse> imported = new ArrayList<>();
            Set<String> tree = new LinkedHashSet<>();
            for (FolderDraft draft : drafts) {
                FolderFilePlan filePlan = plans.getOrDefault(draft.id(), fallbackFolderPlan(rootName, draft));
                filePlan = normalizeFolderFilePlan(rootName, metaFromDraft(draft), filePlan);
                Notebook notebook = notebookService.ensureCollectionPath(
                        userId,
                        filePlan.domainName(),
                        filePlan.projectName(),
                        filePlan.collectionName(),
                        filePlan.notebookDescription()
                );
                tree.add(planTreePath(filePlan));
                imported.add(self.createLibraryItem(
                        userId,
                        notebook.getId(),
                        StringUtils.hasText(filePlan.title()) ? filePlan.title() : stripExtension(draft.originalFilename()),
                        draft.originalFilename(),
                        draft.storedFilename(),
                        draft.targetPath(),
                        draft.file(),
                        draft.extension(),
                        filePlan.categoryPath(),
                        draft.extracted(),
                        filePlan.tags(),
                        false
                ));
                // 入库成功后这个文件不再是孤儿; 失败时下面 catch 仍会清剩余的
                orphanCandidates.remove(draft.targetPath());
            }

            return new FolderImportResponse(rootName, planner, new ArrayList<>(tree), imported, aiTracer.drain());
        } catch (RuntimeException exception) {
            // 入库失败: 清掉所有还没成功落库的物理文件, 避免孤儿 PDF 占盘
            for (Path orphan : orphanCandidates) {
                try {
                    Files.deleteIfExists(orphan);
                } catch (IOException ignored) {
                    // 个别清理失败不阻断主流程; 后续可加定时清理任务回收
                }
            }
            throw exception;
        }
    }

    private ImportLibraryItemResponse createLibraryItem(Long userId,
                                                        Long notebookId,
                                                        String resolvedTitle,
                                                        String originalFilename,
                                                        String storedFilename,
                                                        Path targetPath,
                                                        MultipartFile file,
                                                        String extension,
                                                        String detectedCategory,
                                                        ExtractedContent extracted,
                                                        List<String> tagNames) {
        return createLibraryItem(userId, notebookId, resolvedTitle, originalFilename, storedFilename, targetPath, file, extension, detectedCategory, extracted, tagNames, true);
    }

    /**
     * 创建一条资料库条目: 写笔记 + 写 LibraryItem + 关标签. 必须包成事务,
     * 否则三者任何一步失败都会留下孤儿数据 (note 没 tag, LibraryItem 指向不存在的 note 等).
     * importItem / autoImport 已经有外层 @Transactional, 这里 REQUIRED 直接合并.
     * importFolder 没有外层事务, 这里会自己起新事务, 单文件失败不影响其他文件.
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportLibraryItemResponse createLibraryItem(Long userId,
                                                        Long notebookId,
                                                        String resolvedTitle,
                                                        String originalFilename,
                                                        String storedFilename,
                                                        Path targetPath,
                                                        MultipartFile file,
                                                        String extension,
                                                        String detectedCategory,
                                                        ExtractedContent extracted,
                                                        List<String> tagNames,
                                                        boolean allowAiNote) {
        String visibleContent = buildNoteContent(resolvedTitle, originalFilename, detectedCategory, extracted, tagNames, allowAiNote);
        Note note = noteService.createWithExtraIndexText(userId, new SaveNoteRequest(
                notebookId,
                resolvedTitle,
                visibleContent,
                buildSummary(detectedCategory, extracted)
        ), extracted.text());

        LibraryItem item = new LibraryItem();
        item.setUserId(userId);
        item.setNotebookId(notebookId);
        item.setNoteId(note.getId());
        item.setTitle(resolvedTitle);
        item.setOriginalFilename(originalFilename);
        item.setStoredFilename(storedFilename);
        item.setStoragePath(targetPath.toString());
        item.setContentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/octet-stream");
        item.setFileExt(extension);
        item.setFileSize(file.getSize());
        item.setCategory(detectedCategory);
        item.setStatus(extracted.supported() ? "READY" : "STORED");
        item.setExtractedText(extracted.text());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        item.setDeleted(0);
        libraryItemMapper.insert(item);

        List<Tag> tags = tagService.setNoteTagsByName(userId, note.getId(), tagNames);
        Notebook notebook = notebookMapper.selectById(notebookId);
        return new ImportLibraryItemResponse(
                item.getId(),
                note.getId(),
                notebookId,
                notebook == null ? "" : notebook.getName(),
                notebookPath(userId, notebook),
                note.getTitle(),
                detectedCategory,
                tags.stream().map(Tag::getName).toList(),
                originalFilename,
                "/api/library/" + item.getId() + "/file"
        );
    }

    public List<LibraryItemView> list(Long userId, Long notebookId) {
        return list(userId, notebookId, false);
    }

    public List<LibraryItemView> list(Long userId, Long notebookId, boolean recursive) {
        List<Long> notebookIds = null;
        if (notebookId != null && recursive) {
            notebookIds = notebookService.descendantNotebookIds(userId, notebookId);
            if (notebookIds.isEmpty()) return List.of();
        }
        LambdaQueryWrapper<LibraryItem> wrapper = new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getDeleted, 0)
                .eq(notebookId != null && !recursive, LibraryItem::getNotebookId, notebookId)
                .in(notebookIds != null, LibraryItem::getNotebookId, notebookIds)
                .orderByDesc(LibraryItem::getCreatedAt)
                .orderByDesc(LibraryItem::getId);

        return libraryItemMapper.selectList(wrapper).stream()
                .map(item -> toView(userId, item))
                .toList();
    }

    public DownloadedLibraryFile getFile(Long userId, Long itemId) {
        LibraryItem item = requireOwnedItem(userId, itemId);
        try {
            Path path = ownedStoragePath(userId, item.getStoragePath());
            return new DownloadedLibraryFile(
                    new UrlResource(path.toUri()),
                    item.getOriginalFilename(),
                    item.getContentType()
            );
        } catch (MalformedURLException exception) {
            throw new BizException("资料文件路径无效");
        }
    }

    public String exportArchiveFilename(Long userId, Long notebookId) {
        Notebook notebook = notebookId == null ? null : notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        String base = notebook == null ? "Atlas资料导出" : safeZipSegment(notebook.getName());
        return base + "_原文件.zip";
    }

    public void writeArchive(Long userId, Long notebookId, boolean recursive, OutputStream outputStream) {
        List<Long> notebookIds = null;
        if (notebookId != null && recursive) {
            notebookIds = notebookService.descendantNotebookIds(userId, notebookId);
            if (notebookIds.isEmpty()) notebookIds = List.of(notebookId);
        }
        LambdaQueryWrapper<LibraryItem> wrapper = new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getDeleted, 0)
                .eq(notebookId != null && !recursive, LibraryItem::getNotebookId, notebookId)
                .in(notebookIds != null, LibraryItem::getNotebookId, notebookIds)
                .orderByAsc(LibraryItem::getNotebookId)
                .orderByAsc(LibraryItem::getCategory)
                .orderByAsc(LibraryItem::getOriginalFilename)
                .orderByAsc(LibraryItem::getId);
        List<LibraryItem> items = libraryItemMapper.selectList(wrapper);

        Set<String> usedEntries = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (LibraryItem item : items) {
                Path path;
                try {
                    path = ownedStoragePath(userId, item.getStoragePath());
                } catch (BizException exception) {
                    missing.add(item.getOriginalFilename() + " -> " + item.getStoragePath());
                    continue;
                }
                String entryName = uniqueZipEntryName(archiveEntryName(userId, item), usedEntries);
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
            if (items.isEmpty()) {
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write("这个范围内没有可导出的资料文件。".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            } else if (!missing.isEmpty()) {
                zip.putNextEntry(new ZipEntry("_导出说明/缺失文件.txt"));
                zip.write(("以下文件在数据库中有记录，但磁盘原文件不存在，已跳过：\n\n" + String.join("\n", missing)).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("资料导出失败", exception);
        }
    }

    public void deleteItem(Long userId, Long itemId, String mode) {
        LibraryItem item = requireOwnedItem(userId, itemId);
        String normalized = StringUtils.hasText(mode) ? mode : "item";
        if ("item".equals(normalized) || "files".equals(normalized)) {
            markItemDeleted(userId, itemId);
            return;
        }
        if ("note".equals(normalized)) {
            if (item.getNoteId() != null && item.getNoteId() > 0) {
                noteService.deleteWithMode(userId, item.getNoteId(), "note");
            }
            detachItemNote(userId, itemId);
            return;
        }
        if ("all".equals(normalized)) {
            markItemDeleted(userId, itemId);
            if (item.getNoteId() != null && item.getNoteId() > 0) {
                noteService.deleteWithMode(userId, item.getNoteId(), "note");
            }
            return;
        }
        throw new BizException("删除模式只能是 note / files / item / all");
    }

    private void markItemDeleted(Long userId, Long itemId) {
        int changed = libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                .set(LibraryItem::getDeleted, 1)
                .set(LibraryItem::getUpdatedAt, LocalDateTime.now())
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getId, itemId));
        if (changed == 0) {
            throw new BizException(404, "资料不存在或已删除");
        }
    }

    private void detachItemNote(Long userId, Long itemId) {
        libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                .set(LibraryItem::getNoteId, 0L)
                .set(LibraryItem::getUpdatedAt, LocalDateTime.now())
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getId, itemId));
    }

    private LibraryItem requireOwnedItem(Long userId, Long itemId) {
        LibraryItem item = libraryItemMapper.selectOne(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getId, itemId)
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getDeleted, 0)
                .last("limit 1"));
        if (item == null) {
            throw new BizException(404, "资料不存在");
        }
        return item;
    }

    private LibraryItemView toView(Long userId, LibraryItem item) {
        return new LibraryItemView(
                item.getId(),
                item.getNoteId(),
                item.getTitle(),
                item.getOriginalFilename(),
                item.getContentType(),
                item.getFileExt(),
                item.getFileSize(),
                item.getCategory(),
                item.getStatus(),
                "/api/library/" + item.getId() + "/file",
                item.getNotebookId(),
                notebookPath(userId, notebookMapper.selectById(item.getNotebookId())),
                item.getCreatedAt()
        );
    }

    private ExtractedContent extract(Long userId, Path path, String originalFilename, String extension, String contentType) {
        if (documentConversionService.shouldConvert(extension, contentType)) {
            ConvertedDocument converted = documentConversionService.convert(userId, path, originalFilename, extension, contentType);
            String text = normalizeText(converted.text());
            if (text.length() > MAX_EXTRACTED_TEXT) {
                text = text.substring(0, MAX_EXTRACTED_TEXT)
                        + "\n\n[内容过长，MinerU 已转换，Atlas 当前仅索引前 " + MAX_EXTRACTED_TEXT + " 字符]";
            }
            return new ExtractedContent(
                    true,
                    text,
                    "已通过 MinerU 转换 " + originalFilename + " 为可搜索 Markdown。",
                    null
            );
        }

        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean textLike = extension.equals("txt")
                || extension.equals("md")
                || extension.equals("markdown")
                || extension.equals("html")
                || extension.equals("htm")
                || lowerType.startsWith("text/");
        if (!textLike) {
            return new ExtractedContent(false, "", "当前文件已保存原件，后续阶段会加入该格式的内容解析。", null);
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            String raw = decodeText(bytes);
            String text = extension.equals("html") || extension.equals("htm") || lowerType.contains("html")
                    ? htmlToReadableText(raw)
                    : raw;
            text = normalizeText(text);
            if (text.length() > MAX_EXTRACTED_TEXT) {
                text = text.substring(0, MAX_EXTRACTED_TEXT) + "\n\n[内容过长，MVP 阶段仅索引前 " + MAX_EXTRACTED_TEXT + " 字符]";
            }
            return new ExtractedContent(true, text, "已提取 " + originalFilename + " 的可读文本。", null);
        } catch (IOException exception) {
            throw new UncheckedIOException("资料文本解析失败", exception);
        }
    }

    private String buildNoteContent(String title, String originalFilename, String category, ExtractedContent extracted, List<String> tags, boolean allowAiNote) {
        if (allowAiNote) {
            String aiContent = buildAiNoteContent(title, originalFilename, category, extracted, tags);
            if (StringUtils.hasText(aiContent)) return aiContent;
        }
        return buildRuleNoteContent(title, originalFilename, category, extracted, tags);
    }

    private String buildRuleNoteContent(String title, String originalFilename, String category, ExtractedContent extracted, List<String> tags) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(title.trim()).append("\n\n")
                .append("> 来源文件：").append(originalFilename).append("\n")
                .append("> 自动分类：").append(category).append("\n")
                .append("> 原文件：见标题下方“打开原文件”按钮\n");
        if (tags != null && !tags.isEmpty()) {
            builder.append("> 标签：").append(String.join(" ", tags.stream().map(tag -> "#" + tag).toList())).append("\n");
        }

        builder.append("\n")
                .append("## 这份资料讲了什么\n\n")
                .append(ruleSummary(category, extracted)).append("\n\n")
                .append("## 关键线索\n\n")
                .append(keyLines(extracted.text())).append("\n\n")
                .append("## 适合这样检索\n\n")
                .append(searchHints(title, category, tags)).append("\n\n")
                .append("## 可继续追问\n\n")
                .append("- 这份资料的重点是什么？\n")
                .append("- 根据原文件内容，整理一份练习/阅读/复盘清单。\n")
                .append("- 找出这份资料中和某个关键词相关的片段。\n");
        return builder.toString();
    }

    private String buildAiNoteContent(String title, String originalFilename, String category, ExtractedContent extracted, List<String> tags) {
        if (!extracted.supported() || !StringUtils.hasText(extracted.text())) return "";
        try {
            ChatClient client = chatClientFactory.current();
            if ("local-rag-fallback".equals(client.modelName())) return "";
            String sample = normalizeText(extracted.text());
            if (sample.length() > 12_000) sample = sample.substring(0, 12_000);
            String tagLine = tags == null || tags.isEmpty() ? "" : String.join("、", tags);
            String response = tracedChat.complete("library-ai-note", client, List.of(
                    new ChatClient.Message("system", """
                            你是 Atlas 个人知识库入库助手。请把用户导入的文件整理成一篇 Markdown 笔记。
                            要求：
                            - 不要复刻原文，不要输出大段原始 HTML 或全文。
                            - 目标是便于 RAG 召回和用户回看，所以要写清楚这份文件讲了什么、关键点、适合怎么搜索。
                            - 原文件已经由系统单独保存，笔记里只写“原文件：见标题下方打开原文件按钮”，不要编造本地路径或链接。
                            - 如果是课程/课堂笔记，优先整理成课程主题、知识点、练习建议。
                            - 如果是网页/HTML 笔记，保留“网页内容讲了什么”和“值得回看的部分”，不要描述 HTML 标签结构。
                            - 只输出 Markdown。
                            """),
                    new ChatClient.Message("user", """
                            标题：%s
                            来源文件：%s
                            自动分类：%s
                            标签：%s

                            可读文本片段：
                            %s
                            """.formatted(title, originalFilename, category, tagLine, sample))
            ));
            String markdown = normalizeAiMarkdown(response);
            if (!StringUtils.hasText(markdown)) return "";
            if (!markdown.startsWith("#")) {
                markdown = "# " + title.trim() + "\n\n" + markdown;
            }
            return markdown;
        } catch (Exception exception) {
            log.warn("[Library] AI markdown generation failed, fallback to imported file summary, title={}, file={}",
                    title, originalFilename, exception);
            return "";
        }
    }

    private String buildSummary(String category, ExtractedContent extracted) {
        if (!extracted.supported()) {
            return "已归档为 " + category + "，原文件已保存。";
        }
        String text = normalizeText(extracted.text());
        if (!StringUtils.hasText(text)) {
            return "已归档为 " + category + "，未提取到可读文本。";
        }
        return "已归档为 " + category + "，原文已进入搜索索引。";
    }

    private String ruleSummary(String category, ExtractedContent extracted) {
        if (!extracted.supported()) return "系统已保存原文件，当前格式暂未解析正文。";
        if (!StringUtils.hasText(extracted.text())) return "系统没有从文件中提取到可读文本，但原文件已保存。";
        return "系统已从文件中提取可读文本，并把原文写入搜索索引和 RAG 知识库。当前显示的是整理笔记，不直接展示原文全文。分类为：" + category + "。";
    }

    private String keyLines(String text) {
        String cleaned = normalizeText(text);
        if (!StringUtils.hasText(cleaned)) return "- 未提取到可读文本。";
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String line : cleaned.split("\\n")) {
            String item = line.trim();
            if (item.length() < 8) continue;
            if (item.length() > 90) item = item.substring(0, 90) + "...";
            builder.append("- ").append(item).append("\n");
            count++;
            if (count >= 6) break;
        }
        return count == 0 ? "- 已提取文本，但没有足够清晰的短句可展示。" : builder.toString().trim();
    }

    private String searchHints(String title, String category, List<String> tags) {
        List<String> hints = new ArrayList<>();
        hints.add(title);
        hints.add(category);
        if (tags != null) hints.addAll(tags);
        return hints.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(8)
                .map(value -> "- " + value)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 可以用标题、分类、文件内关键词搜索。");
    }

    private String normalizeAiMarkdown(String text) {
        if (text == null) return "";
        String cleaned = text.trim()
                .replaceAll("(?is)^```(?:markdown|md)?\\s*", "")
                .replaceAll("(?is)```\\s*$", "")
                .trim();
        cleaned = cleaned.replaceAll("(?im)^\\s*\\[.*?]\\(/api/library/\\d+/file\\)\\s*$", "").trim();
        if (!cleaned.toLowerCase(Locale.ROOT).contains("原文件")) {
            cleaned = cleaned + "\n\n> 原文件：见标题下方“打开原文件”按钮";
        }
        return cleaned;
    }

    private String htmlToReadableText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?i)</(p|div|section|article|h[1-6]|li|br|tr)>", "\n")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private String decodeText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        long replacementCount = utf8.chars().filter(ch -> ch == '\uFFFD').count();
        if (replacementCount > Math.max(8, utf8.length() / 80)) {
            return new String(bytes, Charset.forName("GBK"));
        }
        return utf8;
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \t\\x0B\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private AutoPlan planAutoImport(String originalFilename, String extension, String contentType, String text) {
        AutoPlan aiPlan = planWithAi(originalFilename, extension, contentType, text);
        if (aiPlan != null) return aiPlan;
        return planWithRules(originalFilename, extension, contentType, text);
    }

    private AutoPlan planWithAi(String originalFilename, String extension, String contentType, String text) {
        try {
            ChatClient client = chatClientFactory.current();
            if ("local-rag-fallback".equals(client.modelName())) return null;
            String sample = normalizeText(text);
            if (sample.length() > 6000) sample = sample.substring(0, 6000);
            String response = tracedChat.complete("library-auto-plan", client, List.of(
                    new ChatClient.Message("system", """
                            你是 Atlas 文件入库分类器。只输出 JSON，不要输出解释。
                            根据文件名、扩展名、MIME、内容片段判断：
                            - domainName：最上层领域，例如“小说”“研究”“艺术学习”“个人资料”。
                            - projectName：领域下的项目，例如“神兵绝响”“钢琴”“论文阅读”。
                            - collectionName：真正承载文件和笔记的资料库，例如“设定集”“课堂笔记”“论文库”。
                            - notebookName：兼容字段，等于 collectionName。
                            - 不要把文件格式当成顶层，例如不要创建“网页资料库”这种顶层领域。
                            - notebookDescription：一句话描述这个库。
                            - categoryPath：递归分类路径，用 / 分隔，例如“课堂笔记/贝多芬/月光奏鸣曲”。
                            - tags：3 到 8 个短标签，不要带 #。
                            如果文件名体现主题，优先相信文件名。
                            """),
                    new ChatClient.Message("user", """
                            文件名：%s
                            扩展名：%s
                            MIME：%s
                            内容片段：
                            %s
                            """.formatted(originalFilename, extension, contentType, sample))
            ));
            JsonNode root = MAPPER.readTree(extractJson(response));
            String notebookName = safeText(root.path("notebookName").asText(""));
            String description = safeText(root.path("notebookDescription").asText(""));
            String categoryPath = safeText(root.path("categoryPath").asText(""));
            List<String> tags = new ArrayList<>();
            if (root.path("tags").isArray()) {
                for (JsonNode tag : root.path("tags")) {
                    String name = safeText(tag.asText(""));
                    if (StringUtils.hasText(name)) tags.add(name);
                }
            }
            if (!StringUtils.hasText(notebookName) || !StringUtils.hasText(categoryPath)) return null;
            if (tags.isEmpty()) tags.add(extension.isBlank() ? "file" : extension);
            String domainName = safeText(root.path("domainName").asText(""));
            String projectName = safeText(root.path("projectName").asText(""));
            String collectionName = safeText(root.path("collectionName").asText(""));
            FolderFilePlan normalized = normalizeFolderFilePlan("", new FolderMeta(0, originalFilename, originalFilename, extension, contentType, null, text),
                    new FolderFilePlan(domainName, projectName, collectionName, notebookName, StringUtils.hasText(description) ? description : "AI 自动整理的资料库", categoryPath, stripExtension(originalFilename), tags, false, ""));
            return new AutoPlan(
                    normalized.domainName(),
                    normalized.projectName(),
                    normalized.collectionName(),
                    normalized.notebookDescription(),
                    normalized.categoryPath(),
                    normalized.tags()
            );
        } catch (Exception exception) {
            log.warn("[Library] AI auto-plan failed, fallback to rules, file={}", originalFilename, exception);
            return null;
        }
    }

    private AutoPlan planWithRules(String originalFilename, String extension, String contentType, String text) {
        String type = detectCategory(extension, contentType, text);
        String lower = (originalFilename + "\n" + text).toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        tags.add(extension.isBlank() ? "file" : extension);

        String filename = stripExtension(originalFilename);
        if (filename.contains("钢琴") || filename.contains("琴课") || filename.toLowerCase(Locale.ROOT).contains("piano")) {
            tags.add("钢琴");
            tags.add("课堂笔记");
            if (filename.contains("月光")) tags.add("月光奏鸣曲");
            return new AutoPlan("艺术学习", "钢琴", "钢琴课堂笔记", "自动收纳钢琴课记录、曲目分析和练习资料", "课堂笔记/" + inferPianoTopic(filename), tags);
        }

        if (extension.equals("pdf")) {
            tags.add("PDF");
            tags.add("论文");
            String topic = topicFromText(lower);
            tags.add(topic);
            return new AutoPlan("研究", "论文阅读", "论文库", "自动收纳 PDF 论文、报告和研究资料", "论文/PDF/" + topic, tags);
        }
        if (extension.equals("txt")) {
            tags.add("文本");
            if (looksLikeNovel(text)) {
                tags.add("小说");
                return new AutoPlan("阅读", "小说与长文", "阅读库", "自动收纳小说、长文、网页和阅读材料", "阅读/小说", tags);
            }
            return new AutoPlan("个人资料", "通用资料", "资料库", "自动收纳通用文本、网页和 Markdown 资料", "资料/文本", tags);
        }
        if (extension.equals("md") || extension.equals("markdown")) {
            tags.add("Markdown");
            return new AutoPlan("个人资料", "通用资料", "笔记库", "自动收纳 Markdown 和个人笔记", "笔记/Markdown", tags);
        }
        if (extension.equals("html") || extension.equals("htm")) {
            tags.add("HTML");
            return new AutoPlan("个人资料", "网页笔记", "网页资料库", "自动收纳 HTML 页面和网页笔记", "网页/HTML", tags);
        }
        if ((contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)).startsWith("image/")) {
            tags.add("图片");
            return new AutoPlan("个人资料", "视觉资料", "图片库", "自动收纳图片和视觉资料", "图片/未整理", tags);
        }
        tags.add(type);
        return new AutoPlan("个人资料", "收件箱", "待分类收件箱", "系统暂时无法高置信度分类的文件", "待分类/" + type, tags);
    }

    private FolderPlanningResult planFolderNamesWithAi(Long userId, String rootName, List<FolderMeta> files, String correction, String previousPlanJson) {
        try {
            ChatClient client = chatClientFactory.current();
            if ("local-rag-fallback".equals(client.modelName())) return new FolderPlanningResult(Map.of(), "rules");
            String manifest = folderNameManifest(files);
            String response = tracedChat.complete("library-folder-plan", client, List.of(
                    new ChatClient.Message("system", """
                            你是 Atlas 文件夹入库规划器。你会看到完整文件夹的文件名、路径、扩展名、MIME 和大小。
                            目标：先用文件名/路径和已有知识库树，生成尽可能少的新节点，以及每个资料库下面的递归分类树。
                            分类原则：
                            - domainName 是最上层领域，例如“小说”“研究”“艺术学习”“个人资料”。
                            - projectName 是领域下的具体项目，例如“神兵绝响”“钢琴”“论文阅读”。
                            - collectionName 是真正承载文件和笔记的资料库，例如“设定集”“课堂笔记”“论文库”。
                            - notebookName 为兼容字段，必须等于 collectionName。
                            - 尽可能复用已有知识库树；现有树能容纳时，不要新建同义节点。
                            - 尽可能少建领域和项目；只有主题完全不相干时才拆出新的 domain/project。
                            - categoryPath 是资料库内部的树形子路径，用 / 分隔，可以参考原目录，但要按语义整理。
                            - 如果这是小说/游戏/世界观设定资料，优先归入：小说 / 作品名 / 设定集。
                            - 结构优先：如果原目录已经是清晰资产树，不要重新发明分类，保留原目录的稳定层级。
                            - 要理解语义并规范中文命名。例如“第八柱_檀照夜”在语义上是“第八女主 檀照夜”，不要改写成乱码编号。
                            - 角色资产的推荐结构是：角色资产/女主/第八女主 檀照夜/人物基础档案、角色资产/女主/第八女主 檀照夜/视觉资产。
                            - 总纲类资料的推荐结构是：总纲资产/母总纲、总纲资产/总纲编译、总纲资产/公用资产。
                            - 根目录脚本放到：工具脚本。
                            - 图片、立绘、截图、视觉素材只根据文件名、路径和扩展名分类，不要因为无法读图就标记为不确定。
                            - 脚本/代码文件第一轮只根据路径和文件名判断用途，例如资产生成、数据清洗、爬取、编译、工具脚本等；文件名看不懂时标 uncertain=true，后续会单文件读全文复判。
                            - 文件名和路径已经很明确的文件，直接给出位置，并设置 uncertain=false。
                            - 文件名混乱、看不懂、只有编号、无法判断主题或明显需要读内容的文件，先给一个临时位置，同时设置 uncertain=true 并写 reason。
                            - 如果用户提供了纠错意见和上一版规划，必须优先按纠错意见重排，但仍要覆盖所有输入文件。
                            输出要求：
                            - 只输出 JSON，不要解释。
                            - files 数组必须覆盖输入中的每个 id。
                            - tags 给 3 到 8 个短标签，不带 #。
                            - title 是文件入库后的笔记标题，可以参考文件名。
                            JSON 格式：
                            {
                              "files": [
                                {
                                  "id": 1,
                                  "domainName": "艺术学习",
                                  "projectName": "钢琴",
                                  "collectionName": "钢琴课堂笔记",
                                  "notebookName": "钢琴课堂笔记",
                                  "notebookDescription": "钢琴课记录、曲目分析和练习资料",
                                  "categoryPath": "课堂笔记/贝多芬/月光奏鸣曲",
                                  "title": "月光奏鸣曲第二、三乐章课堂笔记",
                                  "tags": ["钢琴", "贝多芬", "月光奏鸣曲"],
                                  "uncertain": false,
                                  "reason": ""
                                }
                              ]
                            }
                            """),
                    new ChatClient.Message("user", """
                            根文件夹：%s

                            已有知识库树：
                            %s

                            上一版规划：
                            %s

                            用户纠错意见：
                            %s

                            完整文件清单：
                            %s
                            """.formatted(
                            rootName,
                            existingKnowledgeTree(userId),
                            StringUtils.hasText(previousPlanJson) ? previousPlanJson : "(无)",
                            StringUtils.hasText(correction) ? correction : "(无)",
                            manifest
                    ))
            ));
            String planner = StringUtils.hasText(correction) ? "ai-folder-corrected" : "ai-folder-names";
            return new FolderPlanningResult(parseFolderPlans(response), planner);
        } catch (Exception exception) {
            log.warn("[Library] AI folder naming failed, fallback to structural rules, userId={}, root={}",
                    userId, rootName, exception);
            return new FolderPlanningResult(Map.of(), "rules");
        }
    }

    private FolderPlanningResult polishStructuredPlanWithAi(Long userId, String rootName, List<FolderMeta> files, FolderPlanningResult draft) {
        try {
            ChatClient client = chatClientFactory.current();
            if ("local-rag-fallback".equals(client.modelName())) return draft;
            String response = tracedChat.complete("library-folder-polish", client, List.of(
                    new ChatClient.Message("system", """
                            你是 Atlas 文件夹树审美规划师。系统已经用规则解释器生成了一个高置信草案，你要做的是“精修”，不是从零重来。
                            目标：让知识库树更干净、更适合人类长期浏览，同时保持每个文件都能找到位置。
                            要求：
                            - 必须覆盖输入中每个 id。
                            - 保持 domainName/projectName/collectionName 尽量稳定，除非明显错误。
                            - 可以合并过细或重复的 categoryPath，但不要把完全不同角色混在一起。
                            - 去掉原始目录编号噪声，例如 01_人物基础档案 改为 人物基础档案，05_视觉资产_立绘与表情包 改为 视觉资产。
                            - 角色资产推荐结构：角色资产/女主/第一女主 姬霓裳/人物基础档案。
                            - 主角放：角色资产/主角/叶临渊/...
                            - 核心角色放：角色资产/核心角色/烬璃/...
                            - 总纲资产保留：总纲资产/母总纲、总纲资产/总纲编译、总纲资产/公用资产/世界观与设定总纲。
                            - 图片和立绘归入对应角色的 视觉资产。
                            - 根目录 py/js 等工具归入 工具脚本。
                            - 只输出 JSON，不要解释。JSON 格式与草案一致。
                            """),
                    new ChatClient.Message("user", """
                            根文件夹：%s

                            已有知识库树：
                            %s

                            文件清单：
                            %s

                            规则草案：
                            %s
                            """.formatted(rootName, existingKnowledgeTree(userId), folderNameManifest(files), draftManifest(draft)))
            ));
            Map<Integer, FolderFilePlan> polished = parseFolderPlans(response);
            if (polished.size() < files.size() * 8 / 10) return draft;
            Map<Integer, FolderFilePlan> merged = new HashMap<>(draft.plans());
            merged.putAll(polished);
            return new FolderPlanningResult(merged, "rules-asset-tree+ai-polish");
        } catch (Exception exception) {
            log.warn("[Library] AI folder polish failed, keep structural draft, userId={}, root={}",
                    userId, rootName, exception);
            return draft;
        }
    }

    private String draftManifest(FolderPlanningResult draft) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Integer, FolderFilePlan> entry : draft.plans().entrySet()) {
            FolderFilePlan plan = entry.getValue();
            builder.append("id: ").append(entry.getKey()).append("\n")
                    .append("domainName: ").append(plan.domainName()).append("\n")
                    .append("projectName: ").append(plan.projectName()).append("\n")
                    .append("collectionName: ").append(plan.collectionName()).append("\n")
                    .append("notebookName: ").append(plan.notebookName()).append("\n")
                    .append("categoryPath: ").append(plan.categoryPath()).append("\n")
                    .append("title: ").append(plan.title()).append("\n")
                    .append("tags: ").append(String.join(", ", plan.tags())).append("\n\n");
        }
        return builder.toString();
    }

    private FolderPlanningResult planFolderByStructure(String rootName, List<FolderMeta> files) {
        Map<Integer, FolderFilePlan> plans = new HashMap<>();
        int structured = 0;
        for (FolderMeta file : files) {
            FolderFilePlan plan = structuredAssetPlan(rootName, file);
            if (plan != null) {
                plans.put(file.id(), plan);
                structured++;
            }
        }
        if (files.isEmpty() || structured < Math.max(4, files.size() * 7 / 10)) {
            return new FolderPlanningResult(Map.of(), "rules");
        }
        return new FolderPlanningResult(plans, "rules-asset-tree");
    }

    private FolderFilePlan structuredAssetPlan(String rootName, FolderMeta file) {
        List<String> parts = normalizedAssetParts(rootName, pathParts(file.relativePath()));
        if (parts.isEmpty()) return null;
        String domainName = inferAssetDomain(rootName);
        String projectName = inferAssetProject(rootName);
        String collectionName = "设定集";
        String notebookName = collectionName;
        String description = "按原始资产目录结构整理的设定、角色、视觉与总纲资料";
        String title = stripExtension(file.originalFilename());
        List<String> tags = structuredTags(file);
        String first = parts.get(0);

        if (parts.size() == 1 && isScriptLike(file.extension())) {
            return new FolderFilePlan(domainName, projectName, collectionName, notebookName, description, "工具脚本", title, tags, false, "");
        }
        if (first.equals("_母总纲")) {
            return new FolderFilePlan(domainName, projectName, collectionName, notebookName, description, joinClean("总纲资产", "母总纲", middlePath(parts)), title, tags, false, "");
        }
        if (first.equals("_总纲编译")) {
            return new FolderFilePlan(domainName, projectName, collectionName, notebookName, description, joinClean("总纲资产", "总纲编译", middlePath(parts)), title, tags, false, "");
        }
        if (first.equals("总纲公用资产")) {
            return new FolderFilePlan(domainName, projectName, collectionName, notebookName, description, joinClean("总纲资产", "公用资产", middlePath(parts)), title, tags, false, "");
        }
        if (isCharacterFolder(first)) {
            return new FolderFilePlan(domainName, projectName, collectionName, notebookName, description, joinClean("角色资产", normalizeCharacterFolder(first), middlePath(parts)), title, tags, false, "");
        }
        return null;
    }

    private List<String> normalizedAssetParts(String rootName, List<String> rawParts) {
        List<String> parts = new ArrayList<>(rawParts);
        while (parts.size() > 1) {
            String first = parts.get(0);
            if (first.equals(rootName) || first.equals("资产") || first.equals("image") || first.equals("images")) {
                parts.remove(0);
                continue;
            }
            if (first.equals("神兵绝响") || first.equals("大罗心炉")) {
                parts.remove(0);
                continue;
            }
            break;
        }
        return parts;
    }

    private String inferAssetDomain(String rootName) {
        return "小说";
    }

    private String inferAssetProject(String rootName) {
        if (StringUtils.hasText(rootName)) {
            String clean = rootName.replace("资产", "").replace("设定集", "").trim();
            if (StringUtils.hasText(clean) && !clean.equals("文件夹导入")) return clean;
        }
        return "神兵绝响";
    }

    private String normalizeCharacterFolder(String value) {
        String[] pair = value.split("_", 2);
        if (pair.length < 2) return value;
        String ordinal = pair[0].trim();
        String name = pair[1].trim();
        if (ordinal.equals("主角")) return "主角 " + name;
        if (ordinal.equals("一心")) return "核心角色 " + name;
        if (ordinal.startsWith("第") && ordinal.endsWith("柱")) {
            return ordinal.replace("柱", "女主") + " " + name;
        }
        return value.replace("_", " ");
    }

    private boolean isCharacterFolder(String value) {
        return value.matches("^(主角|一心|第[一二三四五六七八九十]+柱|第\\d+柱)_.+");
    }

    private boolean isScriptLike(String extension) {
        return Set.of("py", "js", "ts", "tsx", "jsx", "java", "go", "rs", "cpp", "c", "cs", "sh", "bat", "cmd", "ps1").contains(extension);
    }

    private String middlePath(List<String> parts) {
        if (parts.size() <= 2) return "";
        return String.join("/", parts.subList(1, parts.size() - 1));
    }

    private String joinClean(String... parts) {
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (StringUtils.hasText(part)) clean.add(part.trim());
        }
        return String.join("/", clean);
    }

    private List<String> structuredTags(FolderMeta file) {
        List<String> tags = new ArrayList<>();
        tags.add(StringUtils.hasText(file.extension()) ? file.extension() : "file");
        for (String part : pathParts(file.relativePath())) {
            String clean = stripExtension(part).trim();
            if (StringUtils.hasText(clean) && tags.size() < 8) tags.add(clean);
        }
        return tags;
    }

    private FolderPlanningResult refineUncertainFolderPlans(String rootName, List<FolderDraft> drafts, FolderPlanningResult planning) {
        Map<Integer, FolderFilePlan> plans = new HashMap<>(planning.plans());
        List<FolderDraft> uncertain = new ArrayList<>();
        for (FolderDraft draft : drafts) {
            FolderFilePlan plan = plans.get(draft.id());
            if (plan == null || plan.uncertain()) uncertain.add(draft);
        }
        if (uncertain.isEmpty()) return planning;

        try {
            ChatClient client = chatClientFactory.current();
            if ("local-rag-fallback".equals(client.modelName())) return planning;
            String response = tracedChat.complete("library-folder-refine", client, List.of(
                    new ChatClient.Message("system", """
                            你是 Atlas 文件夹入库复判器。你会收到上一轮文件名规划里“不确定”的文件，以及这些文件当前可提取到的全文。
                            目标：直接阅读这些文件全文，把它们放回已有知识库树中；只有确实完全不相关时才新建领域/项目/资料库。
                            要求：
                            - 不要按文件格式建“网页资料库/文本资料库/图片库”这种顶层库。
                            - 优先复用已有 domainName / projectName / collectionName 和 categoryPath；可以在已有路径下增加子级。
                            - 只输出 JSON，不要解释。
                            - files 数组只需要覆盖本次给你的不确定 id。
                            JSON 格式同上一轮：
                            {
                              "files": [
                                {
                                  "id": 1,
                                  "domainName": "艺术学习",
                                  "projectName": "钢琴",
                                  "collectionName": "钢琴课堂笔记",
                                  "notebookName": "钢琴课堂笔记",
                                  "notebookDescription": "钢琴课记录、曲目分析和练习资料",
                                  "categoryPath": "课堂笔记/贝多芬/月光奏鸣曲",
                                  "title": "月光奏鸣曲课堂 HTML 笔记",
                                  "tags": ["钢琴", "课堂笔记", "HTML"],
                                  "uncertain": false,
                                  "reason": ""
                                }
                              ]
                            }
                            """),
                    new ChatClient.Message("user", """
                            根文件夹：%s

                            已有知识库树：
                            %s

                            需要直接读全文复判的文件：
                            %s
                            """.formatted(rootName, currentPlanTree(plans), fullTextManifest(uncertain, plans)))
            ));
            Map<Integer, FolderFilePlan> refined = parseFolderPlans(response);
            for (Map.Entry<Integer, FolderFilePlan> entry : refined.entrySet()) {
                plans.put(entry.getKey(), entry.getValue());
            }
            String planner = planning.planner().equals("rules")
                    ? "ai-folder-fulltext"
                    : planning.planner() + "+uncertain-fulltext";
            return new FolderPlanningResult(plans, planner);
        } catch (Exception exception) {
            log.warn("[Library] AI uncertain-file refinement failed, keep previous plan, root={}",
                    rootName, exception);
            return planning;
        }
    }

    private String folderNameManifest(List<FolderMeta> files) {
        StringBuilder builder = new StringBuilder();
        for (FolderMeta file : files) {
            String contentPolicy = isImageFile(file.extension(), file.contentType())
                    ? "图片/视觉文件：只能按文件名和路径分类"
                    : StringUtils.hasText(file.text())
                    ? "脚本/代码文件：请根据下面源码文本分析用途"
                    : "普通文件：按文件名和路径分类";
            builder.append("id: ").append(file.id()).append("\n")
                    .append("path: ").append(file.relativePath()).append("\n")
                    .append("filename: ").append(file.originalFilename()).append("\n")
                    .append("extension: ").append(file.extension().isBlank() ? "unknown" : file.extension()).append("\n")
                    .append("mime: ").append(StringUtils.hasText(file.contentType()) ? file.contentType() : "unknown").append("\n")
                    .append("size: ").append(file.size() == null ? "unknown" : file.size()).append("\n")
                    .append("contentPolicy: ").append(contentPolicy).append("\n");
            if (StringUtils.hasText(file.text())) {
                builder.append("sourceText:\n").append(file.text()).append("\n");
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private boolean isImageFile(String extension, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) return true;
        return Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg").contains(extension);
    }

    private String fullTextManifest(List<FolderDraft> drafts, Map<Integer, FolderFilePlan> plans) {
        StringBuilder builder = new StringBuilder();
        for (FolderDraft draft : drafts) {
            FolderFilePlan plan = plans.getOrDefault(draft.id(), fallbackFolderPlan(rootName(List.of(draft)), draft));
            String text = normalizeText(draft.extracted().text());
            builder.append("id: ").append(draft.id()).append("\n")
                    .append("path: ").append(draft.relativePath()).append("\n")
                    .append("filename: ").append(draft.originalFilename()).append("\n")
                    .append("extension: ").append(draft.extension().isBlank() ? "unknown" : draft.extension()).append("\n")
                    .append("previousNotebook: ").append(plan.notebookName()).append("\n")
                    .append("previousCategoryPath: ").append(plan.categoryPath()).append("\n")
                    .append("uncertainReason: ").append(plan.reason()).append("\n")
                    .append("fullText:\n")
                    .append(text.isBlank() ? "(没有可提取文本，请根据文件名、路径和类型判断)" : text)
                    .append("\n\n---\n\n");
        }
        return builder.toString();
    }

    private String currentPlanTree(Map<Integer, FolderFilePlan> plans) {
        Set<String> tree = new LinkedHashSet<>();
        for (FolderFilePlan plan : plans.values()) {
            if (StringUtils.hasText(plan.notebookName()) && StringUtils.hasText(plan.categoryPath())) {
                tree.add(plan.notebookName() + "/" + plan.categoryPath());
            }
        }
        return tree.isEmpty() ? "(暂无)" : String.join("\n", tree);
    }

    private Map<Integer, FolderFilePlan> parseFolderPlans(String response) throws IOException {
        JsonNode root = MAPPER.readTree(extractJson(response));
        Map<Integer, FolderFilePlan> plans = new HashMap<>();
        if (!root.path("files").isArray()) return plans;
        for (JsonNode file : root.path("files")) {
            int id = file.path("id").asInt(0);
            if (id <= 0) continue;
            FolderFilePlan plan = folderPlanFromJson(file);
            if (plan != null) plans.put(id, plan);
        }
        return plans;
    }

    private FolderFilePlan folderPlanFromJson(JsonNode file) {
        String notebookName = safeText(file.path("notebookName").asText(""));
        String domainName = safeText(file.path("domainName").asText(""));
        String projectName = safeText(file.path("projectName").asText(""));
        String collectionName = safeText(file.path("collectionName").asText(""));
        String description = safeText(file.path("notebookDescription").asText(""));
        String categoryPath = safeText(file.path("categoryPath").asText(""));
        String title = safeText(file.path("title").asText(""));
        String reason = safeText(file.path("reason").asText(""));
        boolean uncertain = file.path("uncertain").asBoolean(false);
        List<String> tags = tagsFromJson(file.path("tags"));
        if (!StringUtils.hasText(notebookName) || !StringUtils.hasText(categoryPath)) return null;
        return normalizeFolderFilePlan("", new FolderMeta(0, title, title, "", "", null, ""), new FolderFilePlan(
                domainName,
                projectName,
                collectionName,
                notebookName,
                StringUtils.hasText(description) ? description : "文件夹批量导入的资料库",
                categoryPath,
                title,
                tags.isEmpty() ? List.of("folder-import") : tags,
                uncertain,
                reason
        ));
    }

    private List<String> tagsFromJson(JsonNode node) {
        List<String> tags = new ArrayList<>();
        if (!node.isArray()) return tags;
        for (JsonNode tag : node) {
            String name = safeText(tag.asText(""));
            if (StringUtils.hasText(name) && tags.size() < 8) tags.add(name);
        }
        return tags;
    }

    private FolderPlanningResult planningFromJson(Long userId, String rootName, String planJson, List<FolderDraft> drafts) {
        try {
            FolderPlanResponse response = MAPPER.readValue(planJson, FolderPlanResponse.class);
            Map<String, FolderDraft> draftsByPath = new HashMap<>();
            for (FolderDraft draft : drafts) draftsByPath.put(draft.relativePath(), draft);
            Map<Integer, FolderFilePlan> plans = new HashMap<>();
            if (response.files() != null) {
                for (FolderPlannedFile file : response.files()) {
                    if (file == null) continue;
                    FolderDraft draft = draftsByPath.get(sanitizeRelativePath(file.path()));
                    if (draft == null) continue;
                    String notebookName = safeText(file.notebookName());
                    String domainName = safeText(file.domainName());
                    String projectName = safeText(file.projectName());
                    String collectionName = safeText(file.collectionName());
                    String categoryPath = safeText(file.categoryPath());
                    if (!StringUtils.hasText(notebookName) || !StringUtils.hasText(categoryPath)) continue;
                    plans.put(draft.id(), normalizeFolderFilePlan(rootName, metaFromDraft(draft), new FolderFilePlan(
                            domainName,
                            projectName,
                            collectionName,
                            notebookName,
                            StringUtils.hasText(file.notebookDescription()) ? safeText(file.notebookDescription()) : "文件夹批量导入的资料库",
                            categoryPath,
                            safeText(file.title()),
                            cleanTags(file.tags(), draft.extension()),
                            file.uncertain(),
                            safeText(file.reason())
                    )));
                }
            }
            return new FolderPlanningResult(plans, StringUtils.hasText(response.planner()) ? response.planner() : "folder-plan-json");
        } catch (Exception exception) {
            log.warn("[Library] failed to parse folder plan JSON, fallback to AI/rules planning, userId={}, root={}",
                    userId, rootName, exception);
            return planFolderNamesWithAi(userId, rootName, drafts.stream().map(this::metaFromDraft).toList(), "", "");
        }
    }

    private FolderPlanResponse folderPlanResponse(Long userId, String rootName, String planner, Map<String, FolderFilePlan> byPath) {
        List<FolderPlannedFile> files = new ArrayList<>();
        Set<String> tree = new LinkedHashSet<>();
        for (Map.Entry<String, FolderFilePlan> entry : byPath.entrySet()) {
            FolderFilePlan plan = entry.getValue();
            tree.add(planTreePath(plan));
            files.add(new FolderPlannedFile(
                    entry.getKey(),
                    plan.domainName(),
                    plan.projectName(),
                    plan.collectionName(),
                    plan.notebookName(),
                    plan.notebookDescription(),
                    plan.categoryPath(),
                    plan.title(),
                    plan.tags(),
                    plan.uncertain(),
                    plan.reason()
            ));
        }
        return new FolderPlanResponse(rootName, planner, databaseSummary(userId, byPath), new ArrayList<>(tree), files);
    }

    private List<String> cleanTags(List<String> rawTags, String fallback) {
        List<String> tags = new ArrayList<>();
        if (rawTags != null) {
            for (String raw : rawTags) {
                String tag = cleanTag(raw);
                if (StringUtils.hasText(tag) && tags.size() < 8) tags.add(tag);
            }
        }
        if (tags.isEmpty()) tags.add(StringUtils.hasText(fallback) ? cleanTag(fallback) : "folder-import");
        return tags;
    }

    private String cleanTag(String raw) {
        String tag = safeText(raw).replace("#", "").replaceAll("\\s+", "-");
        tag = tag.replaceAll("^\\d{4}-\\d{2}-\\d{2}[-_\\d:]*-?", "");
        if (!StringUtils.hasText(tag)) return "";
        if (tag.length() > 24) tag = tag.substring(0, 24);
        return tag;
    }

    private FolderFilePlan normalizeFolderFilePlan(String rootName, FolderMeta meta, FolderFilePlan plan) {
        FolderFilePlan fallback = fallbackFolderPlan(rootName, meta);
        String domainName = cleanTreeName(plan == null ? "" : plan.domainName());
        String projectName = cleanTreeName(plan == null ? "" : plan.projectName());
        String collectionName = cleanTreeName(plan == null ? "" : plan.collectionName());
        String notebookName = cleanTreeName(plan == null ? "" : plan.notebookName());

        if (!StringUtils.hasText(collectionName)) collectionName = notebookName;
        if (!StringUtils.hasText(collectionName)) collectionName = fallback.collectionName();
        if (!StringUtils.hasText(notebookName)) notebookName = collectionName;

        if (!StringUtils.hasText(domainName) || !StringUtils.hasText(projectName)) {
            KnowledgePath inferred = inferKnowledgePath(rootName, meta, collectionName);
            if (!StringUtils.hasText(domainName)) domainName = inferred.domainName();
            if (!StringUtils.hasText(projectName)) projectName = inferred.projectName();
            if (!StringUtils.hasText(collectionName)) collectionName = inferred.collectionName();
        }

        String categoryPath = cleanCategoryPath(plan == null ? "" : plan.categoryPath());
        if (!StringUtils.hasText(categoryPath)) categoryPath = fallback.categoryPath();

        String title = safeText(plan == null ? "" : plan.title());
        if (!StringUtils.hasText(title)) title = stripExtension(meta.originalFilename());

        String description = safeText(plan == null ? "" : plan.notebookDescription());
        if (!StringUtils.hasText(description)) {
            description = "自动整理到“" + domainName + " / " + projectName + " / " + collectionName + "”的资料库";
        }

        List<String> tags = cleanTags(plan == null ? null : plan.tags(), meta.extension());
        return new FolderFilePlan(
                domainName,
                projectName,
                collectionName,
                collectionName,
                description,
                categoryPath,
                title,
                tags,
                plan != null && plan.uncertain(),
                plan == null ? "" : safeText(plan.reason())
        );
    }

    private KnowledgePath inferKnowledgePath(String rootName, FolderMeta meta, String collectionName) {
        String root = StringUtils.hasText(rootName) ? rootName : "文件夹导入";
        String joined = (root + "/" + meta.relativePath() + "/" + collectionName).toLowerCase(Locale.ROOT);
        if (root.contains("神兵绝响") || joined.contains("神兵绝响")) {
            return new KnowledgePath("小说", "神兵绝响", "设定集");
        }
        if (root.contains("大罗心炉") || joined.contains("大罗心炉")) {
            return new KnowledgePath("小说", "大罗心炉", "设定集");
        }
        if (joined.contains("钢琴") || joined.contains("piano")) {
            return new KnowledgePath("艺术学习", "钢琴", StringUtils.hasText(collectionName) ? collectionName : "课堂笔记");
        }
        if (joined.contains("论文") || joined.contains("paper") || meta.extension().equals("pdf")) {
            return new KnowledgePath("研究", "论文阅读", StringUtils.hasText(collectionName) ? collectionName : "论文库");
        }
        return new KnowledgePath("个人资料", root.equals("文件夹导入") ? "收件箱" : root, StringUtils.hasText(collectionName) ? collectionName : "资料库");
    }

    private String planTreePath(FolderFilePlan plan) {
        return joinClean(plan.domainName(), plan.projectName(), plan.collectionName(), plan.categoryPath());
    }

    private String databaseSummary(Long userId, Map<String, FolderFilePlan> byPath) {
        Set<String> collections = new LinkedHashSet<>();
        for (FolderFilePlan plan : byPath.values()) {
            collections.add(joinClean(plan.domainName(), plan.projectName(), plan.collectionName()));
        }
        return "将入库 " + byPath.size() + " 个文件，目标资料库 " + collections.size() + " 个：" + String.join("；", collections);
    }

    private String existingKnowledgeTree(Long userId) {
        if (userId == null || userId <= 0) return "(暂无)";
        List<Notebook> nodes = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0)
                .orderByAsc(Notebook::getParentId)
                .orderByAsc(Notebook::getNodeType)
                .orderByAsc(Notebook::getName));
        if (nodes.isEmpty()) return "(暂无)";
        Map<Long, Notebook> byId = new HashMap<>();
        for (Notebook node : nodes) byId.put(node.getId(), node);
        List<String> lines = new ArrayList<>();
        for (Notebook node : nodes) {
            lines.add(notebookPath(userId, node) + " [" + node.getNodeType() + "]");
        }
        return String.join("\n", lines);
    }

    private String notebookPath(Long userId, Notebook notebook) {
        if (notebook == null) return "";
        Map<Long, Notebook> cache = new HashMap<>();
        List<String> names = new ArrayList<>();
        Notebook cursor = notebook;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            names.add(0, cursor.getName());
            Long parentId = cursor.getParentId();
            if (parentId == null) break;
            cursor = cache.computeIfAbsent(parentId, id -> notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getId, id)
                    .eq(Notebook::getDeleted, 0)
                    .last("limit 1")));
        }
        return String.join(" / ", names);
    }

    private String archiveEntryName(Long userId, LibraryItem item) {
        Notebook notebook = notebookMapper.selectById(item.getNotebookId());
        List<String> parts = new ArrayList<>();
        for (String part : notebookPath(userId, notebook).split("/")) {
            if (StringUtils.hasText(part)) parts.add(safeZipSegment(part));
        }
        if (StringUtils.hasText(item.getCategory())) {
            for (String part : item.getCategory().replace('\\', '/').split("/")) {
                if (StringUtils.hasText(part)) parts.add(safeZipSegment(part));
            }
        }
        String filename = safeZipSegment(item.getOriginalFilename());
        if (!StringUtils.hasText(filename)) filename = safeZipSegment(item.getTitle());
        if (!StringUtils.hasText(filename)) filename = "未命名资料";
        parts.add(filename);
        return String.join("/", parts);
    }

    private String uniqueZipEntryName(String entryName, Set<String> usedEntries) {
        String clean = StringUtils.hasText(entryName) ? entryName : "未命名资料";
        if (usedEntries.add(clean)) return clean;
        int dot = clean.lastIndexOf('.');
        String prefix = dot > 0 ? clean.substring(0, dot) : clean;
        String suffix = dot > 0 ? clean.substring(dot) : "";
        int index = 2;
        while (true) {
            String candidate = prefix + " (" + index++ + ")" + suffix;
            if (usedEntries.add(candidate)) return candidate;
        }
    }

    private String safeZipSegment(String value) {
        if (!StringUtils.hasText(value)) return "";
        String clean = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_")
                .replace("..", "_")
                .trim();
        return clean.length() > 96 ? clean.substring(0, 96).trim() : clean;
    }

    private String cleanTreeName(String value) {
        String clean = safeText(value).replace("/", "／");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private String cleanCategoryPath(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : safeText(value).replace('\\', '/').split("/")) {
            String clean = part.trim();
            if (StringUtils.hasText(clean)) parts.add(clean.length() > 64 ? clean.substring(0, 64) : clean);
        }
        return String.join("/", parts);
    }

    private FolderMeta metaFromDraft(FolderDraft draft) {
        return new FolderMeta(
                draft.id(),
                draft.originalFilename(),
                draft.relativePath(),
                draft.extension(),
                draft.file().getContentType(),
                draft.file().getSize(),
                ""
        );
    }

    private FolderFilePlan fallbackFolderPlan(String rootName, FolderDraft draft) {
        return fallbackFolderPlan(rootName, metaFromDraft(draft), draft.file().getContentType(), draft.extracted().text());
    }

    private FolderFilePlan fallbackFolderPlan(String rootName, FolderMeta file) {
        return fallbackFolderPlan(rootName, file, file.contentType(), "");
    }

    private FolderFilePlan fallbackFolderPlan(String rootName, FolderMeta file, String contentType, String text) {
        List<String> parts = pathParts(file.relativePath());
        String notebookName = StringUtils.hasText(rootName) ? rootName : "文件夹导入";
        String categoryPath;
        if (parts.size() > 1) {
            categoryPath = String.join("/", parts.subList(0, parts.size() - 1));
        } else {
            categoryPath = detectCategory(file.extension(), contentType, text);
        }
        if (!StringUtils.hasText(categoryPath)) {
            categoryPath = "未分类";
        }
        List<String> tags = new ArrayList<>();
        tags.add(file.extension().isBlank() ? "file" : file.extension());
        for (String part : parts) {
            String clean = stripExtension(part).trim();
            if (StringUtils.hasText(clean) && tags.size() < 8) tags.add(clean);
        }
        KnowledgePath inferred = inferKnowledgePath(rootName, file, notebookName);
        String collectionName = inferred.collectionName();
        return new FolderFilePlan(
                inferred.domainName(),
                inferred.projectName(),
                collectionName,
                collectionName,
                "从文件夹“" + notebookName + "”批量导入的资料",
                categoryPath,
                stripExtension(file.originalFilename()),
                tags,
                false,
                ""
        );
    }

    private String sanitizeRelativePath(String value) {
        String path = StringUtils.hasText(value) ? value : "folder-item";
        path = path.replace('\\', '/').replaceAll("[\\r\\n]", " ").trim();
        while (path.startsWith("/")) path = path.substring(1);
        return path.isBlank() ? "folder-item" : path;
    }

    private String filenameFromPath(String path) {
        String normalized = sanitizeRelativePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String rootName(List<FolderDraft> drafts) {
        if (drafts.isEmpty()) return "文件夹导入";
        List<String> parts = pathParts(drafts.get(0).relativePath());
        return parts.size() > 1 ? stripExtension(parts.get(0)) : "文件夹导入";
    }

    private String rootNameFromPaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "文件夹导入";
        List<String> parts = pathParts(paths.get(0));
        return parts.size() > 1 ? stripExtension(parts.get(0)) : "文件夹导入";
    }

    private List<String> pathParts(String path) {
        String[] raw = sanitizeRelativePath(path).split("/");
        List<String> parts = new ArrayList<>();
        for (String item : raw) {
            String clean = item.trim();
            if (StringUtils.hasText(clean)) parts.add(clean);
        }
        return parts;
    }

    private String inferPianoTopic(String filename) {
        if (filename.contains("月光")) return "贝多芬/月光奏鸣曲";
        return "未细分";
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        String cleaned = text.trim()
                .replaceAll("(?is)^```(?:json)?\\s*", "")
                .replaceAll("(?is)```\\s*$", "")
                .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) return cleaned.substring(start, end + 1);
        return cleaned;
    }

    private String safeText(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private String topicFromText(String lower) {
        if (lower.contains("retrieval") || lower.contains("rag") || lower.contains("embedding") || lower.contains("vector")) return "AI/RAG";
        if (lower.contains("database") || lower.contains("sql") || lower.contains("transaction")) return "数据库";
        if (lower.contains("spring") || lower.contains("backend") || lower.contains("microservice")) return "后端工程";
        if (lower.contains("medical") || lower.contains("clinical") || lower.contains("patient")) return "医学";
        if (lower.contains("survey") || lower.contains("review")) return "综述";
        return "未细分";
    }

    private boolean looksLikeNovel(String text) {
        String normalized = normalizeText(text);
        if (normalized.length() > 20_000) return true;
        return normalized.contains("第") && (normalized.contains("章") || normalized.contains("回")) && normalized.length() > 5_000;
    }

    private String detectCategory(String extension, String contentType, String text) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (extension.equals("md") || extension.equals("markdown")) return "Markdown 笔记";
        if (extension.equals("txt")) return looksLikeNovel(text) ? "阅读/小说" : "资料/文本";
        if (extension.equals("html") || extension.equals("htm")) return "HTML 笔记";
        if (lowerType.startsWith("image/")) return "图片";
        if (extension.equals("pdf")) return "PDF 文档";
        if (extension.equals("doc") || extension.equals("docx")) return "Word 文档";
        return "未分类资料";
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的资料文件");
        }
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "library-item";
        }
        String safe = filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return safe.isEmpty() ? "library-item" : safe;
    }

    /**
     * 当前事务回滚时, 删除已落盘的物理文件, 防止孤儿资料文件.
     * 必须在事务方法内调用; importFolder 等无外层事务的场景在外层用 orphanCandidates 列表手动清理.
     */
    private void registerFileRollback(Path targetPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        Files.deleteIfExists(targetPath);
                    } catch (IOException ignored) {
                        // 物理删除失败不影响事务结果
                    }
                }
            }
        });
    }

    private Path ownedStorageRoot(Long userId) {
        return Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId)).normalize();
    }

    private Path ownedStoragePath(Long userId, String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BizException(404, "资料原文件不存在");
        }
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        Path root = ownedStorageRoot(userId);
        if (!path.startsWith(root)) {
            throw new BizException("资料文件路径无效");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BizException(404, "资料原文件不存在");
        }
        return path;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private record ExtractedContent(boolean supported, String text, String message, String fileUrl) {
        ExtractedContent withFileUrl(String newFileUrl) {
            return new ExtractedContent(supported, text, message, newFileUrl);
        }
    }

    private record AutoPlan(String domainName, String projectName, String collectionName, String notebookDescription, String categoryPath, List<String> tags) {
    }

    private record KnowledgePath(String domainName, String projectName, String collectionName) {
    }

    private record FolderDraft(
            int id,
            MultipartFile file,
            String originalFilename,
            String relativePath,
            String storedFilename,
            Path targetPath,
            String extension,
            ExtractedContent extracted
    ) {
    }

    private record FolderMeta(
            int id,
            String originalFilename,
            String relativePath,
            String extension,
            String contentType,
            Long size,
            String text
    ) {
    }

    private record FolderPlanningResult(
            Map<Integer, FolderFilePlan> plans,
            String planner
    ) {
    }

    private record FolderFilePlan(
            String domainName,
            String projectName,
            String collectionName,
            String notebookName,
            String notebookDescription,
            String categoryPath,
            String title,
            List<String> tags,
            boolean uncertain,
            String reason
    ) {
    }

    public record DownloadedLibraryFile(Resource resource, String filename, String contentType) {
    }
}
