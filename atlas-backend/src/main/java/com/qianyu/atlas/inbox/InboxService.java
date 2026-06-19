package com.qianyu.atlas.inbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianyu.atlas.ai.AiAgentService;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.library.LibraryDtos.ImportLibraryItemResponse;
import com.qianyu.atlas.library.LibraryService;
import com.qianyu.atlas.library.LibraryStorageProperties;
import com.qianyu.atlas.note.NoteService;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.notebook.NotebookService;
import com.qianyu.atlas.inbox.InboxDtos.AcceptInboxRequest;
import com.qianyu.atlas.inbox.InboxDtos.AcceptInboxResponse;
import com.qianyu.atlas.inbox.InboxDtos.InboxFileView;
import com.qianyu.atlas.inbox.InboxDtos.InboxRequestView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class InboxService {
    private final InboxRequestMapper requestMapper;
    private final InboxFileMapper fileMapper;
    private final LibraryStorageProperties storageProperties;
    private final LibraryService libraryService;
    private final NoteService noteService;
    private final NotebookService notebookService;
    private final NotebookMapper notebookMapper;
    private final AiAgentService aiAgentService;
    private final ObjectMapper objectMapper;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    public InboxService(InboxRequestMapper requestMapper,
                        InboxFileMapper fileMapper,
                        LibraryStorageProperties storageProperties,
                        LibraryService libraryService,
                        NoteService noteService,
                        NotebookService notebookService,
                        NotebookMapper notebookMapper,
                        AiAgentService aiAgentService,
                        ObjectMapper objectMapper,
                        com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.requestMapper = requestMapper;
        this.fileMapper = fileMapper;
        this.storageProperties = storageProperties;
        this.libraryService = libraryService;
        this.noteService = noteService;
        this.notebookService = notebookService;
        this.notebookMapper = notebookMapper;
        this.aiAgentService = aiAgentService;
        this.objectMapper = objectMapper;
        this.aiTracer = aiTracer;
    }

    public InboxRequestView create(Long userId,
                                   String sourceProject,
                                   String title,
                                   String description,
                                   List<String> paths,
                                   List<MultipartFile> files) {
        if (files == null || files.isEmpty()) throw new BizException("请至少提交一个文件");
        if (files.size() > 100) throw new BizException("一次投递最多 100 个文件，请拆成几批");

        InboxRequest request = new InboxRequest();
        request.setUserId(userId);
        request.setSourceProject(firstText(sourceProject, "外部项目"));
        request.setTitle(firstText(title, request.getSourceProject() + " 入库请求"));
        request.setDescription(StringUtils.hasText(description) ? description.trim() : "");
        request.setStatus(InboxRequest.STATUS_PENDING);
        request.setImportedCount(0);
        request.setFailedCount(0);
        request.setCreatedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now());
        requestMapper.insert(request);

        Path inboxDirectory = inboxDirectory(userId, request.getId());
        try {
            Files.createDirectories(inboxDirectory);
            for (int i = 0; i < files.size(); i++) {
                MultipartFile multipartFile = files.get(i);
                validateFile(multipartFile);
                String originalFilename = sanitizeFilename(multipartFile.getOriginalFilename());
                String extension = extensionOf(originalFilename);
                String storedFilename = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
                Path target = inboxDirectory.resolve(storedFilename).normalize();
                multipartFile.transferTo(target);

                InboxFile file = new InboxFile();
                file.setRequestId(request.getId());
                file.setUserId(userId);
                file.setOriginalFilename(originalFilename);
                file.setRelativePath(sanitizeRelativePath(paths != null && i < paths.size() ? paths.get(i) : originalFilename));
                file.setStoredFilename(storedFilename);
                file.setStoragePath(target.toString());
                file.setContentType(multipartFile.getContentType());
                file.setFileSize(multipartFile.getSize());
                file.setStatus(InboxFile.STATUS_PENDING);
                file.setCreatedAt(LocalDateTime.now());
                file.setUpdatedAt(LocalDateTime.now());
                fileMapper.insert(file);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("外部投递文件保存失败", exception);
        }

        return get(userId, request.getId());
    }

    public List<InboxRequestView> list(Long userId, String status) {
        return requestMapper.selectList(new LambdaQueryWrapper<InboxRequest>()
                        .eq(InboxRequest::getUserId, userId)
                        .eq(StringUtils.hasText(status), InboxRequest::getStatus, status)
                        .orderByDesc(InboxRequest::getUpdatedAt)
                        .orderByDesc(InboxRequest::getId))
                .stream()
                .map(request -> toView(request, filesOf(userId, request.getId())))
                .toList();
    }

    public InboxRequestView get(Long userId, Long requestId) {
        InboxRequest request = requireOwnedRequest(userId, requestId);
        return toView(request, filesOf(userId, requestId));
    }

    public InboxDtos.PlanInboxResponse plan(Long userId, Long requestId, InboxDtos.PlanInboxRequest request) {
        InboxRequest inboxRequest = requireOwnedRequest(userId, requestId);
        List<InboxFile> files = filesOf(userId, requestId);
        if (files.isEmpty()) throw new BizException("这个投递请求没有文件");
        Set<Long> selected = request == null || request.fileIds() == null || request.fileIds().isEmpty()
                ? files.stream().map(InboxFile::getId).collect(HashSet::new, HashSet::add, HashSet::addAll)
                : new HashSet<>(request.fileIds());

        Long fallbackNotebookId = resolveInboxTargetNotebookId(userId, inboxRequest, files, selected, null);
        Notebook fallbackNotebook = notebookMapper.selectById(fallbackNotebookId);
        String fallbackPrefix = smartInboxCategoryPrefix(inboxRequest, files, selected);
        InboxFile readme = findReadmeFile(files, selected);
        String readmeText = readme == null ? "" : readTextFile(readme);

        if (!StringUtils.hasText(readmeText)) {
            return new InboxDtos.PlanInboxResponse(
                    fallbackNotebookId,
                    fallbackNotebook == null ? "" : fallbackNotebook.getName(),
                    targetNotebookPath(userId, fallbackNotebook),
                    fallbackPrefix,
                    "未检测到 README/说明文件，Atlas 使用文件清单和来源信息生成保守计划：整包进入一个知识库，库内按包名归档。",
                    "",
                    "rules-no-readme",
                    List.of("未读取图片内容", "不为每张图片单独建库", "确认后按当前计划批量入库"),
                    aiTracer.drain()
            );
        }

        try {
            String response = aiAgentService.completeWithSystemOverride(
                    request == null ? null : request.agentId(),
                    inboxPlannerSystemPrompt(),
                    inboxPlannerUserPrompt(userId, inboxRequest, files, selected, readme, readmeText),
                    "inbox-plan"
            );
            String json = extractJson(response);
            if (!json.trim().startsWith("{")) {
                throw new BizException("AI 返回了非 JSON 内容，可能是模型按 HTML/Markdown Agent 风格回答，或网关返回了错误页。");
            }
            JsonNode root = objectMapper.readTree(json);
            String target = firstText(root.path("targetNotebookPath").asText(""), root.path("targetNotebookName").asText(""));
            Notebook matchedNotebook = matchCollectionNotebook(userId, target);
            if (matchedNotebook == null) matchedNotebook = fallbackNotebook;
            String categoryPrefix = firstText(root.path("categoryPrefix").asText(""), fallbackPrefix);
            String summary = firstText(root.path("summary").asText(""), "已根据 README 生成入库计划。");
            List<String> steps = jsonStringList(root.path("steps"));
            if (steps.isEmpty()) steps = List.of("读取 README", "匹配一个目标知识库", "确认后批量入库");
            return new InboxDtos.PlanInboxResponse(
                    matchedNotebook == null ? null : matchedNotebook.getId(),
                    matchedNotebook == null ? "" : matchedNotebook.getName(),
                    targetNotebookPath(userId, matchedNotebook),
                    categoryPrefix,
                    summary,
                    readme.getOriginalFilename(),
                    "ai-readme",
                    steps,
                    aiTracer.drain()
            );
        } catch (Exception exception) {
            return new InboxDtos.PlanInboxResponse(
                    fallbackNotebookId,
                    fallbackNotebook == null ? "" : fallbackNotebook.getName(),
                    targetNotebookPath(userId, fallbackNotebook),
                    fallbackPrefix,
                    "README 已找到，但 AI 规划失败，Atlas 使用保守计划。原因：" + trimError(exception.getMessage()),
                    readme.getOriginalFilename(),
                    "rules-ai-fallback",
                    List.of("只读取 README，不读取图片", "使用规则匹配一个目标知识库", "确认后批量入库"),
                    aiTracer.drain()
            );
        }
    }

    @Transactional
    public AcceptInboxResponse accept(Long userId, Long requestId, AcceptInboxRequest request) {
        if (request == null) throw new BizException("缺少投递处理参数");
        boolean aiImport = !"manual".equalsIgnoreCase(firstText(request.importMode(), "ai"));
        if (!aiImport && request.notebookId() == null) throw new BizException("手动入库需要选择目标知识库");
        InboxRequest inboxRequest = requireOwnedRequest(userId, requestId);
        List<InboxFile> files = filesOf(userId, requestId);
        if (files.isEmpty()) throw new BizException("这个投递请求没有文件");

        Set<Long> selected = request.fileIds() == null || request.fileIds().isEmpty()
                ? files.stream().map(InboxFile::getId).collect(HashSet::new, HashSet::add, HashSet::addAll)
                : new HashSet<>(request.fileIds());
        Long targetNotebookId = aiImport
                ? resolveInboxTargetNotebookId(userId, inboxRequest, files, selected, request.notebookId())
                : request.notebookId();
        Notebook targetNotebook = notebookMapper.selectById(targetNotebookId);
        String categoryPrefix = aiImport
                ? firstText(request.categoryPrefix(), smartInboxCategoryPrefix(inboxRequest, files, selected))
                : request.categoryPrefix();
        boolean generateNotes = Boolean.TRUE.equals(request.generateNotes());

        int imported = 0;
        int failed = 0;
        for (InboxFile file : files) {
            if (!selected.contains(file.getId())) {
                markSkipped(file);
                continue;
            }
            if (InboxFile.STATUS_IMPORTED.equals(file.getStatus())) {
                imported += 1;
                continue;
            }
            try {
                DiskMultipartFile multipartFile = new DiskMultipartFile(
                        requireInboxStoragePath(userId, file.getStoragePath()),
                        file.getOriginalFilename(),
                        file.getContentType()
                );
                ImportLibraryItemResponse importedItem = libraryService.importItem(
                        userId,
                        targetNotebookId,
                        stripExtension(file.getOriginalFilename()),
                        aiImport ? smartCategoryFor(file, categoryPrefix) : categoryFor(file, categoryPrefix),
                        multipartFile
                );
                if (generateNotes) {
                    try {
                        noteService.generateAgentNote(
                                userId,
                                importedItem.noteId(),
                                request.agentId(),
                                request.includeCurrentContent()
                        );
                    } catch (Exception exception) {
                        file.setErrorMessage("已入库，但 AI 记忆草稿生成失败：" + trimError(exception.getMessage()));
                        failed += 1;
                    }
                }
                file.setStatus(InboxFile.STATUS_IMPORTED);
                file.setNoteId(importedItem.noteId());
                file.setLibraryItemId(importedItem.itemId());
                file.setUpdatedAt(LocalDateTime.now());
                fileMapper.updateById(file);
                imported += 1;
            } catch (Exception exception) {
                file.setStatus(InboxFile.STATUS_FAILED);
                file.setErrorMessage(trimError(exception.getMessage()));
                file.setUpdatedAt(LocalDateTime.now());
                fileMapper.updateById(file);
                failed += 1;
            }
        }

        inboxRequest.setImportedCount(imported);
        inboxRequest.setFailedCount(failed);
        inboxRequest.setStatus(failed > 0 ? InboxRequest.STATUS_PARTIAL : InboxRequest.STATUS_IMPORTED);
        inboxRequest.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(inboxRequest);
        String targetName = targetNotebook == null ? "" : targetNotebook.getName();
        String targetPath = targetNotebookPath(userId, targetNotebook);
        String summary = (aiImport ? "AI 辅助入库" : "手动入库")
                + "：整条投递只写入一个知识库“"
                + (StringUtils.hasText(targetPath) ? targetPath : targetName)
                + "”，库内分类前缀为“"
                + firstText(categoryPrefix, "未分类")
                + "”。"
                + (generateNotes ? "入库后会生成 Agent 笔记和 VCP 草稿。" : "本次不生成 Agent 笔记/VCP 草稿。");
        return new AcceptInboxResponse(requestId, imported, failed, targetName, targetPath, summary, filesOf(userId, requestId).stream().map(this::toFileView).toList());
    }

    public void reject(Long userId, Long requestId) {
        InboxRequest request = requireOwnedRequest(userId, requestId);
        request.setStatus(InboxRequest.STATUS_REJECTED);
        request.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(request);
        for (InboxFile file : filesOf(userId, requestId)) {
            if (InboxFile.STATUS_PENDING.equals(file.getStatus()) || InboxFile.STATUS_FAILED.equals(file.getStatus())) {
                markSkipped(file);
            }
        }
    }

    private void markSkipped(InboxFile file) {
        file.setStatus(InboxFile.STATUS_SKIPPED);
        file.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(file);
    }

    private InboxRequest requireOwnedRequest(Long userId, Long requestId) {
        InboxRequest request = requestMapper.selectOne(new LambdaQueryWrapper<InboxRequest>()
                .eq(InboxRequest::getUserId, userId)
                .eq(InboxRequest::getId, requestId)
                .last("limit 1"));
        if (request == null) throw new BizException(404, "投递请求不存在");
        return request;
    }

    private List<InboxFile> filesOf(Long userId, Long requestId) {
        return fileMapper.selectList(new LambdaQueryWrapper<InboxFile>()
                .eq(InboxFile::getUserId, userId)
                .eq(InboxFile::getRequestId, requestId)
                .orderByAsc(InboxFile::getId));
    }

    private InboxRequestView toView(InboxRequest request, List<InboxFile> files) {
        return new InboxRequestView(
                request.getId(),
                request.getSourceProject(),
                request.getTitle(),
                request.getDescription(),
                request.getStatus(),
                request.getImportedCount(),
                request.getFailedCount(),
                request.getCreatedAt() == null ? "" : request.getCreatedAt().toString(),
                request.getUpdatedAt() == null ? "" : request.getUpdatedAt().toString(),
                files.stream().map(this::toFileView).toList()
        );
    }

    private InboxFileView toFileView(InboxFile file) {
        return new InboxFileView(
                file.getId(),
                file.getOriginalFilename(),
                file.getRelativePath(),
                file.getContentType(),
                file.getFileSize(),
                file.getStatus(),
                file.getNoteId(),
                file.getLibraryItemId(),
                file.getErrorMessage()
        );
    }

    private Path inboxDirectory(Long userId, Long requestId) {
        return Path.of(storageProperties.getRoot()).toAbsolutePath().normalize()
                .resolve(String.valueOf(userId))
                .resolve("_inbox")
                .resolve(String.valueOf(requestId))
                .normalize();
    }

    private Path requireInboxStoragePath(Long userId, String storagePath) {
        if (userId == null || !StringUtils.hasText(storagePath)) {
            throw new BizException(404, "投递文件不存在");
        }
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        Path root = Path.of(storageProperties.getRoot()).toAbsolutePath().normalize()
                .resolve(String.valueOf(userId))
                .resolve("_inbox")
                .normalize();
        if (!path.startsWith(root)) {
            throw new BizException("投递文件路径无效");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BizException(404, "投递文件不存在");
        }
        return path;
    }

    private String categoryFor(InboxFile file, String prefix) {
        String parent = "";
        String relative = file.getRelativePath();
        if (StringUtils.hasText(relative)) {
            String normalized = sanitizeRelativePath(relative);
            int index = normalized.lastIndexOf('/');
            if (index > 0) parent = normalized.substring(0, index);
        }
        return joinClean(prefix, parent);
    }

    private Long resolveInboxTargetNotebookId(Long userId,
                                              InboxRequest request,
                                              List<InboxFile> files,
                                              Set<Long> selected,
                                              Long preferredNotebookId) {
        if (preferredNotebookId != null) {
            notebookService.ensureCollectionOwner(userId, preferredNotebookId);
            return preferredNotebookId;
        }
        if (looksLikeImageStudio(request) || selectedFilesAreImages(files, selected)) {
            Notebook matched = firstCollectionByNames(userId, List.of("AI图像生成", "AI 图像生成", "表情包素材", "图片收藏"));
            if (matched != null) return matched.getId();
            matched = firstCollectionContaining(userId, List.of("图像", "图片", "素材", "表情"));
            if (matched != null) return matched.getId();
            return notebookService.ensureCollectionPath(
                    userId,
                    "素材库",
                    "",
                    "AI图像生成",
                    "AI Image Studio、表情包和图片素材的统一入口"
            ).getId();
        }
        Notebook matched = firstCollectionByNames(userId, List.of("外部投递", "个人资料", "资料收件箱"));
        if (matched != null) return matched.getId();
        return notebookService.ensureCollectionPath(
                userId,
                "个人资料",
                "",
                "外部投递",
                "其他项目投递过来的待整理资料"
        ).getId();
    }

    private String smartInboxCategoryPrefix(InboxRequest request, List<InboxFile> files, Set<Long> selected) {
        String packName = commonTopFolder(files, selected);
        if (looksLikeImageStudio(request)) {
            return joinClean("表情包", packName);
        }
        if (selectedFilesAreImages(files, selected)) {
            return joinClean("图片素材", packName);
        }
        return joinClean("外部投递", firstText(request.getSourceProject(), "其他项目"), packName);
    }

    private String smartCategoryFor(InboxFile file, String prefix) {
        String category = categoryFor(file, prefix);
        if (isMarkdown(file)) return joinClean(category, "说明");
        return category;
    }

    private InboxFile findReadmeFile(List<InboxFile> files, Set<Long> selected) {
        InboxFile fallback = null;
        for (InboxFile file : files) {
            if (!selected.contains(file.getId())) continue;
            String name = firstText(file.getOriginalFilename(), file.getRelativePath()).toLowerCase(Locale.ROOT);
            boolean textFile = isMarkdown(file) || name.endsWith(".txt");
            if (!textFile) continue;
            if (name.contains("readme")) return file;
            if (name.contains("说明") || name.contains("介绍") || name.contains("manifest")) {
                if (fallback == null) fallback = file;
            }
        }
        return fallback;
    }

    private String readTextFile(InboxFile file) {
        try {
            String text = Files.readString(requireInboxStoragePath(file.getUserId(), file.getStoragePath()), StandardCharsets.UTF_8);
            text = text.replace("\r\n", "\n").replace('\r', '\n').trim();
            return text.length() > 10_000 ? text.substring(0, 10_000) : text;
        } catch (Exception exception) {
            return "";
        }
    }

    private String inboxPlannerSystemPrompt() {
        return """
                你是 Atlas 外部投递入库规划器。你只负责制定计划，不执行入库。
                严格规则：
                - 只根据 README/说明文本、文件名、相对路径和现有知识库树规划。
                - 不要要求读取图片内容，不要猜每张图片具体画面。
                - 一整条投递请求只能进入一个目标知识库，禁止拆成多个知识库。
                - 优先选择现有知识库；只有现有树完全不合适时，summary 里说明建议新建，但 targetNotebookPath 仍尽量选择最接近的现有库。
                - 分类路径是目标知识库内部路径，不要把领域/项目/知识库名重复写进 categoryPrefix。
                - 面向中文用户，命名使用清晰中文。
                - 只输出 JSON，不要 Markdown，不要解释。
                JSON 字段：
                {
                  "targetNotebookPath": "现有知识库完整路径",
                  "targetNotebookName": "现有知识库名",
                  "categoryPrefix": "库内分类路径",
                  "summary": "给用户看的简短计划说明",
                  "steps": ["确认文件", "写入目标库/分类", "向量化入库"]
                }
                """;
    }

    private String inboxPlannerUserPrompt(Long userId,
                                          InboxRequest request,
                                          List<InboxFile> files,
                                          Set<Long> selected,
                                          InboxFile readme,
                                          String readmeText) {
        return """
                来源项目：%s
                投递标题：%s
                投递说明：
                %s

                现有知识库（只能选择一个 collection）：
                %s

                文件清单（只含文件名/路径/MIME/大小，不含图片内容）：
                %s

                优先读取的说明文件：%s
                README/说明内容：
                %s
                """.formatted(
                firstText(request.getSourceProject(), "外部项目"),
                firstText(request.getTitle(), "外部投递"),
                firstText(request.getDescription(), "无"),
                collectionManifest(userId),
                fileManifest(files, selected),
                readme == null ? "未检测到" : firstText(readme.getRelativePath(), readme.getOriginalFilename()),
                readmeText
        );
    }

    private String collectionManifest(Long userId) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getNodeType, Notebook.TYPE_COLLECTION)
                .eq(Notebook::getDeleted, 0)
                .orderByAsc(Notebook::getId));
        if (notebooks.isEmpty()) return "(暂无资料库)";
        List<String> lines = new ArrayList<>();
        for (Notebook notebook : notebooks) {
            if (lines.size() >= 120) break;
            lines.add("- id=" + notebook.getId() + " | " + targetNotebookPath(userId, notebook) + " | " + firstText(notebook.getDescription(), "无描述"));
        }
        return String.join("\n", lines);
    }

    private String fileManifest(List<InboxFile> files, Set<Long> selected) {
        List<String> lines = new ArrayList<>();
        for (InboxFile file : files) {
            if (!selected.contains(file.getId())) continue;
            lines.add("- " + firstText(file.getRelativePath(), file.getOriginalFilename())
                    + " | " + firstText(file.getContentType(), "unknown")
                    + " | " + file.getFileSize() + " bytes");
            if (lines.size() >= 160) break;
        }
        return String.join("\n", lines);
    }

    private Notebook matchCollectionNotebook(Long userId, String target) {
        if (!StringUtils.hasText(target)) return null;
        String cleanTarget = target.trim();
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getNodeType, Notebook.TYPE_COLLECTION)
                .eq(Notebook::getDeleted, 0)
                .orderByAsc(Notebook::getId));
        for (Notebook notebook : notebooks) {
            if (cleanTarget.equals(targetNotebookPath(userId, notebook))) return notebook;
        }
        for (Notebook notebook : notebooks) {
            if (cleanTarget.equals(notebook.getName())) return notebook;
        }
        for (Notebook notebook : notebooks) {
            String path = targetNotebookPath(userId, notebook);
            if (path.contains(cleanTarget) || cleanTarget.contains(path)) return notebook;
        }
        return null;
    }

    private List<String> jsonStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (StringUtils.hasText(value)) values.add(value.trim());
            if (values.size() >= 8) break;
        }
        return values;
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

    private boolean looksLikeImageStudio(InboxRequest request) {
        String text = (firstText(request.getSourceProject(), "") + " " + firstText(request.getTitle(), "") + " " + firstText(request.getDescription(), "")).toLowerCase(Locale.ROOT);
        return text.contains("ai image studio")
                || text.contains("表情包")
                || text.contains("贴纸")
                || text.contains("生图");
    }

    private boolean selectedFilesAreImages(List<InboxFile> files, Set<Long> selected) {
        int total = 0;
        int images = 0;
        for (InboxFile file : files) {
            if (!selected.contains(file.getId())) continue;
            if (isMarkdown(file)) continue;
            total += 1;
            if (isImage(file)) images += 1;
        }
        return total > 0 && images == total;
    }

    private boolean isImage(InboxFile file) {
        String type = firstText(file.getContentType(), "").toLowerCase(Locale.ROOT);
        String extension = extensionOf(file.getOriginalFilename());
        return type.startsWith("image/") || List.of("png", "jpg", "jpeg", "webp", "gif", "bmp").contains(extension);
    }

    private boolean isMarkdown(InboxFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        return extension.equals("md") || extension.equals("markdown");
    }

    private Notebook firstCollectionByNames(Long userId, List<String> names) {
        for (String name : names) {
            Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getNodeType, Notebook.TYPE_COLLECTION)
                    .eq(Notebook::getName, name)
                    .eq(Notebook::getDeleted, 0)
                    .orderByAsc(Notebook::getId)
                    .last("limit 1"));
            if (notebook != null) return notebook;
        }
        return null;
    }

    private Notebook firstCollectionContaining(Long userId, List<String> keywords) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getNodeType, Notebook.TYPE_COLLECTION)
                .eq(Notebook::getDeleted, 0)
                .orderByAsc(Notebook::getId));
        for (Notebook notebook : notebooks) {
            String name = firstText(notebook.getName(), "");
            for (String keyword : keywords) {
                if (name.contains(keyword)) return notebook;
            }
        }
        return null;
    }

    private String commonTopFolder(List<InboxFile> files, Set<Long> selected) {
        String common = "";
        for (InboxFile file : files) {
            if (!selected.contains(file.getId())) continue;
            String relative = sanitizeRelativePath(file.getRelativePath());
            int slash = relative.indexOf('/');
            if (slash <= 0) continue;
            String top = relative.substring(0, slash);
            if (!StringUtils.hasText(common)) {
                common = top;
            } else if (!common.equals(top)) {
                return "";
            }
        }
        return common;
    }

    private String targetNotebookPath(Long userId, Notebook notebook) {
        if (notebook == null) return "";
        List<String> names = new java.util.ArrayList<>();
        Notebook cursor = notebook;
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            names.add(0, cursor.getName());
            Long parentId = cursor.getParentId();
            if (parentId == null) break;
            cursor = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getId, parentId)
                    .eq(Notebook::getDeleted, 0)
                    .last("limit 1"));
        }
        return String.join(" / ", names);
    }

    private String joinClean(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String clean = sanitizeRelativePath(part);
            if (!StringUtils.hasText(clean)) continue;
            if (!builder.isEmpty()) builder.append("/");
            builder.append(clean);
        }
        return builder.isEmpty() ? "外部投递" : builder.toString();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("投递文件不能为空");
        if (file.getSize() > 80L * 1024 * 1024) throw new BizException("单个投递文件不能超过 80MB");
    }

    private String sanitizeRelativePath(String value) {
        if (!StringUtils.hasText(value)) return "";
        String clean = value.replace("\\", "/")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("/+", "/")
                .trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        clean = clean.replace("..", "");
        return clean.length() > 480 ? clean.substring(clean.length() - 480) : clean;
    }

    private String sanitizeFilename(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "未命名文件";
        name = name.replace("\\", "/");
        int index = name.lastIndexOf('/');
        if (index >= 0) name = name.substring(index + 1);
        name = name.replaceAll("[\\r\\n\\t]+", " ").trim();
        return name.isBlank() ? "未命名文件" : name;
    }

    private String extensionOf(String filename) {
        int index = filename == null ? -1 : filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) return "";
        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        String clean = sanitizeFilename(filename);
        int index = clean.lastIndexOf('.');
        return index > 0 ? clean.substring(0, index) : clean;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String trimError(String message) {
        if (!StringUtils.hasText(message)) return "未知错误";
        String clean = message.trim();
        return clean.length() > 900 ? clean.substring(0, 900) : clean;
    }

    private static class DiskMultipartFile implements MultipartFile {
        private final Path path;
        private final String filename;
        private final String contentType;

        private DiskMultipartFile(Path path, String filename, String contentType) {
            this.path = path;
            this.filename = filename;
            this.contentType = StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return getSize() == 0;
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException exception) {
                return 0;
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            if (Files.exists(path)) return Files.newInputStream(path);
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void transferTo(Path dest) throws IOException, IllegalStateException {
            Files.createDirectories(dest.getParent());
            Files.copy(path, dest);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            transferTo(dest.toPath());
        }
    }
}
