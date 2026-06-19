package com.qianyu.atlas.paper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.chat.ChatClient;
import com.qianyu.atlas.chat.ChatClientFactory;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.document.ConvertedDocument;
import com.qianyu.atlas.document.DocumentConversionService;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteDtos.SaveNoteRequest;
import com.qianyu.atlas.note.NoteService;
import com.qianyu.atlas.paper.PaperDtos.ImportPaperResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaperService {
    private static final int MAX_AI_PDF_TEXT = 45_000;
    private static final int MAX_STORED_PAPER_TEXT = 240_000;

    private final PaperAttachmentMapper paperAttachmentMapper;
    private final PaperStorageProperties properties;
    private final NoteService noteService;
    private final ChatClientFactory chatClientFactory;
    private final DocumentConversionService documentConversionService;
    private final com.qianyu.atlas.ai.TracedChatHelper tracedChat;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    public PaperService(PaperAttachmentMapper paperAttachmentMapper,
                        PaperStorageProperties properties,
                        NoteService noteService,
                        ChatClientFactory chatClientFactory,
                        DocumentConversionService documentConversionService,
                        com.qianyu.atlas.ai.TracedChatHelper tracedChat,
                        com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.paperAttachmentMapper = paperAttachmentMapper;
        this.properties = properties;
        this.noteService = noteService;
        this.chatClientFactory = chatClientFactory;
        this.documentConversionService = documentConversionService;
        this.tracedChat = tracedChat;
        this.aiTracer = aiTracer;
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportPaperResponse importPaper(Long userId,
                                           Long notebookId,
                                           String title,
                                           String summary,
                                           String markdownContent,
                                           MultipartFile file) {
        validateImport(title, markdownContent, file);

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + ".pdf";
        Path userDirectory = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId));
        Path targetPath = userDirectory.resolve(storedFilename).normalize();

        try {
            Files.createDirectories(userDirectory);
            file.transferTo(targetPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("论文文件归档失败", exception);
        }
        registerFileRollback(targetPath);

        Note note = noteService.create(userId, new SaveNoteRequest(
                notebookId,
                title.trim(),
                buildNoteContent(title, markdownContent, null),
                normalizeSummary(summary)
        ));

        PaperAttachment attachment = new PaperAttachment();
        attachment.setUserId(userId);
        attachment.setNotebookId(notebookId);
        attachment.setNoteId(note.getId());
        attachment.setOriginalFilename(originalFilename);
        attachment.setStoredFilename(storedFilename);
        attachment.setStoragePath(targetPath.toString());
        attachment.setContentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/pdf");
        attachment.setFileSize(file.getSize());
        attachment.setExtractedText(markdownContent);
        attachment.setCreatedAt(LocalDateTime.now());
        attachment.setUpdatedAt(LocalDateTime.now());
        attachment.setDeleted(0);
        paperAttachmentMapper.insert(attachment);

        Note updated = noteService.update(userId, note.getId(), new SaveNoteRequest(
                notebookId,
                title.trim(),
                buildNoteContent(title, markdownContent, attachment.getId()),
                normalizeSummary(summary)
        ));

        return new ImportPaperResponse(
                attachment.getId(),
                updated.getId(),
                updated.getTitle(),
                originalFilename,
                "/api/papers/" + attachment.getId() + "/file"
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public ImportPaperResponse importPaperWithAi(Long userId,
                                                 Long notebookId,
                                                 String titleHint,
                                                 MultipartFile file) {
        validatePdfFile(file);

        StoredPaper stored = storePdf(userId, file);
        registerFileRollback(stored.targetPath());
        String extractedText = extractPaperText(userId, stored, file.getContentType());
        if (!StringUtils.hasText(extractedText)) {
            throw new BizException("PDF 未提取到可读文本，可能是扫描版图片 PDF；当前 MVP 还未接 OCR");
        }
        String promptText = trimForAiPrompt(extractedText);

        String fallbackTitle = StringUtils.hasText(titleHint) ? titleHint.trim() : stripPdfSuffix(stored.originalFilename());
        ChatClient client = chatClientFactory.current();
        String aiMarkdown;
        try {
            aiMarkdown = tracedChat.complete("paper-ai", client, List.of(
                    new ChatClient.Message("system", aiPaperSystemPrompt()),
                    new ChatClient.Message("user", aiPaperUserPrompt(fallbackTitle, stored.originalFilename(), promptText))
            ));
        } catch (Exception exception) {
            throw new BizException(500, "AI 论文入库失败，请检查 AI 设置中的 Chat 模型：" + exception.getMessage());
        }
        aiMarkdown = normalizeAiMarkdown(aiMarkdown, fallbackTitle);

        String noteTitle = extractTitleFromMarkdown(aiMarkdown, fallbackTitle);
        String summary = extractSectionSnippet(aiMarkdown, "中文摘要");
        if (!StringUtils.hasText(summary)) {
            summary = "AI 已根据论文 PDF 生成结构化论文笔记，并进入知识库。";
        }

        Note note = noteService.create(userId, new SaveNoteRequest(
                notebookId,
                noteTitle,
                buildNoteContent(noteTitle, aiMarkdown, null),
                trim(summary, 512)
        ));

        PaperAttachment attachment = new PaperAttachment();
        attachment.setUserId(userId);
        attachment.setNotebookId(notebookId);
        attachment.setNoteId(note.getId());
        attachment.setOriginalFilename(stored.originalFilename());
        attachment.setStoredFilename(stored.storedFilename());
        attachment.setStoragePath(stored.targetPath().toString());
        attachment.setContentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : "application/pdf");
        attachment.setFileSize(file.getSize());
        attachment.setExtractedText(trimStoredText(extractedText));
        attachment.setCreatedAt(LocalDateTime.now());
        attachment.setUpdatedAt(LocalDateTime.now());
        attachment.setDeleted(0);
        paperAttachmentMapper.insert(attachment);

        Note updated = noteService.update(userId, note.getId(), new SaveNoteRequest(
                notebookId,
                noteTitle,
                buildNoteContent(noteTitle, aiMarkdown, attachment.getId()),
                trim(summary, 512)
        ));

        return new ImportPaperResponse(
                attachment.getId(),
                updated.getId(),
                updated.getTitle(),
                stored.originalFilename(),
                "/api/papers/" + attachment.getId() + "/file",
                aiTracer.drain()
        );
    }

    public DownloadedPaper getPaperFile(Long userId, Long attachmentId) {
        PaperAttachment attachment = requireOwnedAttachment(userId, attachmentId);
        try {
            Path path = ownedStoragePath(userId, attachment.getStoragePath());
            Resource resource = new UrlResource(path.toUri());
            return new DownloadedPaper(resource, attachment.getOriginalFilename(), attachment.getContentType());
        } catch (MalformedURLException exception) {
            throw new BizException("论文原文路径无效");
        }
    }

    private PaperAttachment requireOwnedAttachment(Long userId, Long attachmentId) {
        PaperAttachment attachment = paperAttachmentMapper.selectOne(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getId, attachmentId)
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getDeleted, 0)
                .last("limit 1"));
        if (attachment == null) {
            throw new BizException(404, "论文附件不存在");
        }
        return attachment;
    }

    private void validateImport(String title, String markdownContent, MultipartFile file) {
        if (!StringUtils.hasText(title)) {
            throw new BizException("请填写论文标题");
        }
        if (title.trim().length() > 128) {
            throw new BizException("论文标题不能超过 128 个字符");
        }
        if (!StringUtils.hasText(markdownContent)) {
            throw new BizException("请粘贴论文 Markdown 内容");
        }
        validatePdfFile(file);
    }

    private void validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请上传论文 PDF 文件");
        }
        String filename = file.getOriginalFilename();
        boolean isPdfName = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
        boolean isPdfType = file.getContentType() != null && file.getContentType().toLowerCase(Locale.ROOT).contains("pdf");
        if (!isPdfName && !isPdfType) {
            throw new BizException("当前只支持 PDF 论文文件");
        }
    }

    private StoredPaper storePdf(Long userId, MultipartFile file) {
        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + ".pdf";
        Path userDirectory = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId));
        Path targetPath = userDirectory.resolve(storedFilename).normalize();

        try {
            Files.createDirectories(userDirectory);
            file.transferTo(targetPath);
            return new StoredPaper(originalFilename, storedFilename, targetPath);
        } catch (IOException exception) {
            throw new UncheckedIOException("论文文件归档失败", exception);
        }
    }

    private String extractPdfText(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = normalizeText(stripper.getText(document));
            if (text.length() > MAX_AI_PDF_TEXT) {
                text = text.substring(0, MAX_AI_PDF_TEXT) + "\n\n[PDF 文本较长，AI 入库阶段仅使用前 " + MAX_AI_PDF_TEXT + " 字符生成初版笔记]";
            }
            return text;
        } catch (IOException exception) {
            throw new UncheckedIOException("PDF 文本提取失败", exception);
        }
    }

    private String extractPaperText(Long userId, StoredPaper stored, String contentType) {
        if (documentConversionService.shouldConvert("pdf", contentType)) {
            ConvertedDocument converted = documentConversionService.convert(
                    userId,
                    stored.targetPath(),
                    stored.originalFilename(),
                    "pdf",
                    contentType
            );
            return normalizeText(converted.text());
        }
        return extractPdfText(stored.targetPath());
    }

    private String trimForAiPrompt(String text) {
        String cleaned = normalizeText(text);
        if (cleaned.length() <= MAX_AI_PDF_TEXT) return cleaned;
        return cleaned.substring(0, MAX_AI_PDF_TEXT)
                + "\n\n[PDF 文本较长，AI 入库阶段仅使用前 " + MAX_AI_PDF_TEXT + " 字符生成初版笔记]";
    }

    private String trimStoredText(String text) {
        String cleaned = normalizeText(text);
        if (cleaned.length() <= MAX_STORED_PAPER_TEXT) return cleaned;
        return cleaned.substring(0, MAX_STORED_PAPER_TEXT)
                + "\n\n[PDF 文本较长，Atlas 当前仅保存前 " + MAX_STORED_PAPER_TEXT + " 字符用于检索]";
    }

    private String buildNoteContent(String title, String markdownContent, Long attachmentId) {
        String noteBody = markdownContent.trim()
                .replace("导入后由 Atlas 自动补充 PDF 链接", "见上方原文件按钮");

        return "# " + title.trim() + "\n\n"
                + "## 论文笔记与可搜索内容\n\n"
                + noteBody + "\n";
    }

    private String aiPaperSystemPrompt() {
        return """
                你是 Atlas 的论文入库助手。请把用户提供的 PDF 文本整理成可长期保存的 Markdown 论文笔记。
                要求：
                1. 只根据提供的 PDF 文本写，不要编造论文中没有的信息。
                2. 如果某项无法确定，写“未在当前 PDF 文本中明确找到”。
                3. 输出必须是 Markdown，不要包裹代码块。
                4. 内容使用中文为主，保留必要英文术语。
                5. 结构必须包含：中英文标题、自动分类、中文摘要、论文讲了什么、创新点、实际应用、方法与实验、局限与注意、论文链接占位、可继续追问。
                """;
    }

    private String aiPaperUserPrompt(String titleHint, String originalFilename, String extractedText) {
        return """
                请根据下面的论文 PDF 文本生成一篇结构化论文笔记。

                标题线索：%s
                来源文件：%s
                论文链接：导入后由 Atlas 自动补充 PDF 链接

                请严格使用这个 Markdown 模板：

                # 中文标题 / English Title

                > 自动分类：写一个简短分类，例如 AI / 机器学习 / 数据库 / 医学影像 / 综述 / 工程实践
                > 来源文件：%s
                > 论文链接：导入后由 Atlas 自动补充 PDF 链接

                ## 中英文标题

                - 中文标题：
                - English Title：

                ## 中文摘要

                ## 论文讲了什么

                ## 创新点

                ## 实际应用

                ## 方法与实验

                ## 局限与注意

                ## 关键词

                ## 可继续追问

                PDF 文本：
                %s
                """.formatted(titleHint, originalFilename, originalFilename, extractedText);
    }

    private String normalizeAiMarkdown(String markdown, String fallbackTitle) {
        String cleaned = normalizeText(markdown);
        cleaned = cleaned.replaceAll("(?is)^```(?:markdown|md)?\\s*", "").replaceAll("(?is)```\\s*$", "").trim();
        if (!StringUtils.hasText(cleaned)) {
            return "# " + fallbackTitle + "\n\nAI 未返回有效论文笔记。";
        }
        if (!cleaned.startsWith("# ")) {
            cleaned = "# " + fallbackTitle + "\n\n" + cleaned;
        }
        return cleaned;
    }

    private String extractTitleFromMarkdown(String markdown, String fallbackTitle) {
        for (String line : markdown.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).trim();
                return trim(StringUtils.hasText(title) ? title : fallbackTitle, 128);
            }
        }
        return trim(fallbackTitle, 128);
    }

    private String extractSectionSnippet(String markdown, String section) {
        String marker = "## " + section;
        int start = markdown.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = markdown.indexOf("\n## ", start);
        String value = end < 0 ? markdown.substring(start) : markdown.substring(start, end);
        return normalizeText(value.replaceAll("^[:：\\s]+", ""));
    }

    private String normalizeSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return "论文已归档，正文 Markdown 已进入知识库，可用于搜索和问答。";
        }
        return summary.trim();
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t\\x0B\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String trim(String text, int maxLen) {
        String cleaned = normalizeText(text);
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen);
    }

    private String stripPdfSuffix(String filename) {
        if (!StringUtils.hasText(filename)) return "未命名论文";
        return filename.replaceFirst("(?i)\\.pdf$", "");
    }

    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "paper.pdf";
        }
        String safe = filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return safe.isEmpty() ? "paper.pdf" : safe;
    }

    private Path ownedStorageRoot(Long userId) {
        return Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId)).normalize();
    }

    private Path ownedStoragePath(Long userId, String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BizException(404, "论文原文文件不存在");
        }
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        Path root = ownedStorageRoot(userId);
        if (!path.startsWith(root)) {
            throw new BizException("论文原文路径无效");
        }
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BizException(404, "论文原文文件不存在");
        }
        return path;
    }

    /**
     * 当前事务回滚时, 删除已经落盘的物理文件, 防止留下孤儿 PDF.
     * 必须在事务方法内调用, 在事务外调用则不注册.
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
                        // 物理删除失败不影响事务结果, 由后续清理任务回收
                    }
                }
            }
        });
    }

    public record DownloadedPaper(Resource resource, String filename, String contentType) {
    }

    private record StoredPaper(String originalFilename, String storedFilename, Path targetPath) {
    }
}
