package com.qianyu.atlas.note;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qianyu.atlas.ai.AiAgentService;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.NoteDtos.SaveNoteRequest;
import com.qianyu.atlas.note.NoteDtos.AgentNoteResponse;
import com.qianyu.atlas.note.NoteDtos.NoteDetail;
import com.qianyu.atlas.note.NoteDtos.NoteFileLink;
import com.qianyu.atlas.note.NoteDtos.NoteHistoryItem;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.notebook.NotebookService;
import com.qianyu.atlas.paper.PaperAttachment;
import com.qianyu.atlas.paper.PaperAttachmentMapper;
import com.qianyu.atlas.paper.PaperStorageProperties;
import com.qianyu.atlas.rag.EmbeddingPipeline;
import com.qianyu.atlas.vcp.VcpMemoryDraftService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class NoteService {
    private final NoteMapper noteMapper;
    private final NoteHistoryMapper noteHistoryMapper;
    private final NotebookMapper notebookMapper;
    private final EmbeddingPipeline embeddingPipeline;
    private final LibraryItemMapper libraryItemMapper;
    private final PaperAttachmentMapper paperAttachmentMapper;
    private final NotebookService notebookService;
    private final AiAgentService aiAgentService;
    private final VcpMemoryDraftService vcpMemoryDraftService;
    private final PaperStorageProperties paperStorageProperties;
    private final Executor agentExecutor;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    public NoteService(NoteMapper noteMapper,
                       NoteHistoryMapper noteHistoryMapper,
                       NotebookMapper notebookMapper,
                       EmbeddingPipeline embeddingPipeline,
                       LibraryItemMapper libraryItemMapper,
                       PaperAttachmentMapper paperAttachmentMapper,
                       NotebookService notebookService,
                       AiAgentService aiAgentService,
                       VcpMemoryDraftService vcpMemoryDraftService,
                       PaperStorageProperties paperStorageProperties,
                       @Qualifier("agentExecutor") Executor agentExecutor,
                       com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.noteMapper = noteMapper;
        this.noteHistoryMapper = noteHistoryMapper;
        this.notebookMapper = notebookMapper;
        this.embeddingPipeline = embeddingPipeline;
        this.libraryItemMapper = libraryItemMapper;
        this.paperAttachmentMapper = paperAttachmentMapper;
        this.notebookService = notebookService;
        this.aiAgentService = aiAgentService;
        this.vcpMemoryDraftService = vcpMemoryDraftService;
        this.paperStorageProperties = paperStorageProperties;
        this.agentExecutor = agentExecutor;
        this.aiTracer = aiTracer;
    }

    @Transactional(rollbackFor = Exception.class)
    public Note create(Long userId, SaveNoteRequest request) {
        ensureNotebookOwner(userId, request.notebookId());

        Note note = new Note();
        note.setUserId(userId);
        note.setNotebookId(request.notebookId());
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setSummary(request.summary());
        note.setSearchText(buildSearchText(request.title(), request.summary(), request.content()));
        noteMapper.insert(note);
        scheduleEmbedding(note);
        return note;
    }

    @Transactional(rollbackFor = Exception.class)
    public Note createWithExtraIndexText(Long userId, SaveNoteRequest request, String extraText) {
        ensureNotebookOwner(userId, request.notebookId());

        String indexContent = StringUtils.hasText(extraText)
                ? request.content() + "\n\n" + extraText
                : request.content();
        Note note = new Note();
        note.setUserId(userId);
        note.setNotebookId(request.notebookId());
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setSummary(request.summary());
        note.setSearchText(buildSearchText(request.title(), request.summary(), indexContent));
        noteMapper.insert(note);
        embeddingPipeline.rebuildChunks(note.getUserId(), note.getId(), note.getTitle(), indexContent);
        embeddingPipeline.scheduleEmbedAfterCommit(note.getId());
        return note;
    }

    @Transactional(rollbackFor = Exception.class)
    public Note update(Long userId, Long noteId, SaveNoteRequest request) {
        Note note = requireOwnedNote(userId, noteId);
        ensureNotebookOwner(userId, request.notebookId());

        // 更新前把当前(旧)内容存为历史快照
        saveHistorySnapshot(note);

        note.setNotebookId(request.notebookId());
        note.setTitle(request.title());
        note.setContent(request.content());
        note.setSummary(request.summary());
        note.setSearchText(buildSearchText(request.title(), request.summary(), request.content()));
        noteMapper.updateById(note);
        Note saved = noteMapper.selectById(noteId);
        scheduleEmbedding(saved);
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void rebuildIndexWithExtraText(Long userId, Long noteId, String extraText) {
        Note note = requireOwnedNote(userId, noteId);
        String indexContent = StringUtils.hasText(extraText)
                ? note.getContent() + "\n\n" + extraText
                : note.getContent();
        note.setSearchText(buildSearchText(note.getTitle(), note.getSummary(), indexContent));
        noteMapper.updateById(note);
        embeddingPipeline.rebuildChunks(note.getUserId(), note.getId(), note.getTitle(), indexContent);
        embeddingPipeline.scheduleEmbedAfterCommit(note.getId());
    }

    public AgentNoteResponse generateAgentNote(Long userId, Long noteId, Long agentId, Boolean includeCurrentContent) {
        Note note = requireOwnedNote(userId, noteId);
        AgentSources sources = sourceTextForAgent(userId, noteId);
        if (!sources.hasText()) {
            throw new BizException("当前笔记没有可发送给 Agent 的原文件文本。请先导入 txt/md/html/pdf 等可提取文本的附件。");
        }
        String content = aiAgentService.complete(
                agentId,
                defaultAgentSystemPrompt(),
                agentNoteUserPrompt(note, sources.text(), Boolean.TRUE.equals(includeCurrentContent)),
                "note-agent"
        );
        String cleaned = content == null ? "" : content.trim();
        vcpMemoryDraftService.captureFromAgentNote(note, cleaned);
        return new AgentNoteResponse(agentId, cleaned, aiTracer.drain());
    }

    public SseEmitter streamAgentNote(Long userId, Long noteId, Long agentId, Boolean includeCurrentContent) {
        Note note = requireOwnedNote(userId, noteId);
        AgentSources sources = sourceTextForAgent(userId, noteId);
        if (!sources.hasText()) {
            throw new BizException("当前笔记没有可发送给 Agent 的原文件文本。请先导入 txt/md/html/pdf 等可提取文本的附件。");
        }
        String userPrompt = agentNoteUserPrompt(note, sources.text(), Boolean.TRUE.equals(includeCurrentContent));
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                aiAgentService.completeStream(agentId, defaultAgentSystemPrompt(), userPrompt, "note-agent-stream", delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(delta));
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(exception.getMessage()));
                } catch (Exception sendException) {
                    log.debug("[NoteAgent] failed to send SSE error event, noteId={}", noteId, sendException);
                }
                emitter.completeWithError(exception);
            }
        }, agentExecutor);
        return emitter;
    }

    private void scheduleEmbedding(Note note) {
        embeddingPipeline.rebuildChunks(note.getUserId(), note.getId(), note.getTitle(), note.getContent());
        embeddingPipeline.scheduleEmbedAfterCommit(note.getId());
    }

    public Note get(Long userId, Long noteId) {
        return requireOwnedNote(userId, noteId);
    }

    public NoteDetail getDetail(Long userId, Long noteId) {
        Note note = requireOwnedNote(userId, noteId);
        return new NoteDetail(
                note.getId(),
                note.getUserId(),
                note.getNotebookId(),
                note.getTitle(),
                note.getContent(),
                note.getSummary(),
                note.getCreatedAt(),
                note.getUpdatedAt(),
                fileLinks(userId, noteId)
        );
    }

    public List<Note> listByNotebook(Long userId, Long notebookId) {
        ensureNotebookOwner(userId, notebookId);
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getNotebookId, notebookId)
                .eq(Note::getDeleted, 0)
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId));
    }

    public List<Note> listByNotebook(Long userId, Long notebookId, boolean recursive) {
        if (!recursive) return listByNotebook(userId, notebookId);
        List<Long> notebookIds = notebookService.descendantNotebookIds(userId, notebookId);
        if (notebookIds.isEmpty()) return List.of();
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .in(Note::getNotebookId, notebookIds)
                .eq(Note::getDeleted, 0)
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId));
    }

    public List<Note> search(Long userId, String keyword, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 50);
        return noteMapper.search(userId, keyword, safeLimit);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long noteId) {
        Note note = requireOwnedNote(userId, noteId);
        embeddingPipeline.deleteIndex(userId, note.getId());
        noteMapper.deleteById(note.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWithMode(Long userId, Long noteId, String mode) {
        Note note = requireOwnedNote(userId, noteId);
        String normalized = StringUtils.hasText(mode) ? mode : "note";
        if ("note".equals(normalized)) {
            embeddingPipeline.deleteIndex(userId, note.getId());
            noteMapper.deleteById(note.getId());
            detachFiles(userId, noteId);
            return;
        }
        if ("files".equals(normalized)) {
            deleteFiles(userId, noteId);
            return;
        }
        if ("all".equals(normalized)) {
            deleteFiles(userId, noteId);
            embeddingPipeline.deleteIndex(userId, note.getId());
            noteMapper.deleteById(note.getId());
            return;
        }
        throw new BizException("删除模式只能是 note / files / all");
    }

    private List<NoteFileLink> fileLinks(Long userId, Long noteId) {
        List<NoteFileLink> links = new ArrayList<>();
        List<LibraryItem> items = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNoteId, noteId)
                .eq(LibraryItem::getDeleted, 0));
        for (LibraryItem item : items) {
            links.add(new NoteFileLink(
                    "打开原文件",
                    "/api/library/" + item.getId() + "/file",
                    item.getContentType(),
                    "library"
            ));
        }
        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNoteId, noteId)
                .eq(PaperAttachment::getDeleted, 0));
        for (PaperAttachment paper : papers) {
            links.add(new NoteFileLink(
                    "打开论文 PDF",
                    "/api/papers/" + paper.getId() + "/file",
                    paper.getContentType(),
                    "paper"
            ));
        }
        return links;
    }

    private AgentSources sourceTextForAgent(Long userId, Long noteId) {
        StringBuilder builder = new StringBuilder();
        int sourceCount = 0;
        int textSourceCount = 0;
        List<LibraryItem> items = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNoteId, noteId)
                .eq(LibraryItem::getDeleted, 0));
        for (LibraryItem item : items) {
            sourceCount++;
            builder.append("【原文件】").append(item.getOriginalFilename()).append("\n")
                    .append("类型：").append(item.getContentType()).append("\n")
                    .append("分类：").append(item.getCategory()).append("\n");
            if (StringUtils.hasText(item.getExtractedText())) {
                textSourceCount++;
                builder.append("正文：\n")
                        .append(trimForAgent(item.getExtractedText(), 45_000))
                        .append("\n\n");
            } else {
                builder.append("正文：未提取到可发送文本。\n\n");
            }
        }

        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNoteId, noteId)
                .eq(PaperAttachment::getDeleted, 0));
        for (PaperAttachment paper : papers) {
            sourceCount++;
            builder.append("【论文附件】").append(paper.getOriginalFilename()).append("\n")
                    .append("类型：").append(paper.getContentType()).append("\n");
            String paperText = paperTextForAgent(paper);
            if (StringUtils.hasText(paperText)) {
                textSourceCount++;
                builder.append("正文：\n")
                        .append(trimForAgent(paperText, 45_000))
                        .append("\n\n");
            } else {
                builder.append("正文：未提取到可发送文本，可能是扫描版 PDF 或文件已不存在。\n\n");
            }
        }
        return new AgentSources(builder.toString(), sourceCount, textSourceCount);
    }

    private String paperTextForAgent(PaperAttachment paper) {
        if (StringUtils.hasText(paper.getExtractedText())) {
            return paper.getExtractedText();
        }
        Path path = ownedPaperPath(paper);
        if (path == null) {
            return "";
        }
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(document);
            if (StringUtils.hasText(text)) {
                paper.setExtractedText(text);
                paper.setUpdatedAt(LocalDateTime.now());
                paperAttachmentMapper.updateById(paper);
            }
            return text;
        } catch (Exception exception) {
            log.warn("[NoteAgent] failed to extract paper text for agent note, paperId={}, noteId={}",
                    paper.getId(), paper.getNoteId(), exception);
            return "";
        }
    }

    private Path ownedPaperPath(PaperAttachment paper) {
        if (paper == null || !StringUtils.hasText(paper.getStoragePath()) || paper.getUserId() == null) {
            return null;
        }
        Path path = Path.of(paper.getStoragePath()).toAbsolutePath().normalize();
        Path root = Path.of(paperStorageProperties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(paper.getUserId())).normalize();
        if (!path.startsWith(root) || !Files.exists(path) || !Files.isRegularFile(path)) {
            return null;
        }
        return path;
    }

    private String agentNoteUserPrompt(Note note, String sourceText, boolean includeCurrentContent) {
        String noteContext = includeCurrentContent
                ? """

                当前 Atlas 笔记内容（作为已有整理结果和用户上下文参考，不要机械复述）：
                %s
                """.formatted(trimForAgent(note.getContent(), 20_000))
                : "\n当前 Atlas 笔记内容：本次未发送，请只依据原文件/附件内容。\n";
        return """
                请基于下面发送的资料生成一段新的双轨补充笔记。
                如果发送了当前 Atlas 笔记内容，它只是上下文参考；核心依据仍是原文件/附件内容。
                必须严格输出下面两个块，两个块之外不要输出任何解释、标题、寒暄、总结或 DailyNote 文本：
                <<<ATLAS_HUMAN_NOTE>>>
                给人看的阅读笔记，可用 Markdown 或安全 HTML。内容要像追加讲解，不要声明替换原笔记。不要包含 VCP_AI_MEMORY、Tag 行或 DailyNote 工具字段。
                <<<END_ATLAS_HUMAN_NOTE>>>

                <<<VCP_AI_MEMORY>>>
                给 AI/RAG/VCP 使用的高密度记忆草稿，纯文本或 Markdown，最后必须有 Tag:。不要包含 HTML、CSS、按钮或视觉排版代码。
                <<<END_VCP_AI_MEMORY>>>

                禁止输出真实 DailyNote TOOL_REQUEST，禁止调用工具，禁止说已经写入日记。

                当前资料标题：
                %s

                原文件/附件提取内容：
                %s

                %s
                """.formatted(
                note.getTitle(),
                sourceText,
                noteContext
        );
    }

    private String defaultAgentSystemPrompt() {
        return """
                你是 Atlas 知识库 Agent。你的任务是把原始资料整理成双轨笔记：
                Atlas 人类笔记给用户阅读，VCP AI 记忆给 RAG 和长期记忆使用。
                人类笔记要清晰、美观、可回看；AI 记忆要高密度、结构化、少噪声、便于语义召回。
                """;
    }

    private String trimForAgent(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        String clean = text.trim();
        return clean.length() > max ? clean.substring(0, max) + "\n\n[内容过长，已截断]" : clean;
    }

    private record AgentSources(String text, int sourceCount, int textSourceCount) {
        boolean hasText() {
            return textSourceCount > 0;
        }
    }

    private void deleteFiles(Long userId, Long noteId) {
        List<LibraryItem> items = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNoteId, noteId)
                .eq(LibraryItem::getDeleted, 0));
        for (LibraryItem item : items) {
            libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                    .set(LibraryItem::getDeleted, 1)
                    .set(LibraryItem::getUpdatedAt, LocalDateTime.now())
                    .eq(LibraryItem::getUserId, userId)
                    .eq(LibraryItem::getId, item.getId()));
        }
        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNoteId, noteId)
                .eq(PaperAttachment::getDeleted, 0));
        for (PaperAttachment paper : papers) {
            paper.setDeleted(1);
            paper.setUpdatedAt(LocalDateTime.now());
            paperAttachmentMapper.updateById(paper);
        }
    }

    private void detachFiles(Long userId, Long noteId) {
        List<LibraryItem> items = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNoteId, noteId)
                .eq(LibraryItem::getDeleted, 0));
        for (LibraryItem item : items) {
            libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                    .set(LibraryItem::getNoteId, 0L)
                    .set(LibraryItem::getUpdatedAt, LocalDateTime.now())
                    .eq(LibraryItem::getUserId, userId)
                    .eq(LibraryItem::getId, item.getId()));
        }
        List<PaperAttachment> papers = paperAttachmentMapper.selectList(new LambdaQueryWrapper<PaperAttachment>()
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNoteId, noteId)
                .eq(PaperAttachment::getDeleted, 0));
        for (PaperAttachment paper : papers) {
            paper.setNoteId(0L);
            paper.setUpdatedAt(LocalDateTime.now());
            paperAttachmentMapper.updateById(paper);
        }
    }

    private void saveHistorySnapshot(Note note) {
        int nextVersion = noteHistoryMapper.selectCount(
                new LambdaQueryWrapper<NoteHistory>().eq(NoteHistory::getNoteId, note.getId())
        ).intValue() + 1;
        NoteHistory history = new NoteHistory();
        history.setNoteId(note.getId());
        history.setUserId(note.getUserId());
        history.setTitle(note.getTitle());
        history.setContent(note.getContent());
        history.setSummary(note.getSummary());
        history.setNoteVersion(nextVersion);
        history.setCreatedAt(LocalDateTime.now());
        noteHistoryMapper.insert(history);
    }

    public List<NoteHistoryItem> listHistory(Long userId, Long noteId) {
        requireOwnedNote(userId, noteId);
        List<NoteHistory> histories = noteHistoryMapper.selectList(new LambdaQueryWrapper<NoteHistory>()
                .eq(NoteHistory::getNoteId, noteId)
                .eq(NoteHistory::getUserId, userId)
                .orderByDesc(NoteHistory::getNoteVersion)
                .orderByDesc(NoteHistory::getId));
        List<NoteHistoryItem> items = new ArrayList<>(histories.size());
        for (NoteHistory history : histories) {
            items.add(new NoteHistoryItem(
                    history.getId(),
                    history.getNoteVersion(),
                    history.getTitle(),
                    excerpt(history.getSummary(), 100),
                    history.getCreatedAt()
            ));
        }
        return items;
    }

    @Transactional(rollbackFor = Exception.class)
    public NoteDetail rollback(Long userId, Long noteId, Long historyId) {
        Note note = requireOwnedNote(userId, noteId);
        NoteHistory history = noteHistoryMapper.selectOne(new LambdaQueryWrapper<NoteHistory>()
                .eq(NoteHistory::getId, historyId)
                .eq(NoteHistory::getNoteId, noteId)
                .eq(NoteHistory::getUserId, userId)
                .last("limit 1"));
        if (history == null) {
            throw new BizException(404, "历史版本不存在");
        }

        // 保底:把当前内容也存一条历史,确保回滚前的状态可恢复
        saveHistorySnapshot(note);

        note.setTitle(history.getTitle());
        note.setContent(history.getContent());
        note.setSummary(history.getSummary());
        note.setSearchText(buildSearchText(history.getTitle(), history.getSummary(), history.getContent()));
        noteMapper.updateById(note);
        Note saved = noteMapper.selectById(noteId);
        scheduleEmbedding(saved);
        return getDetail(userId, noteId);
    }

    private String excerpt(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        String clean = text.trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private Note requireOwnedNote(Long userId, Long noteId) {
        Note note = noteMapper.selectOne(new LambdaQueryWrapper<Note>()
                .eq(Note::getId, noteId)
                .eq(Note::getUserId, userId)
                .last("limit 1"));
        if (note == null) {
            throw new BizException(404, "笔记不存在");
        }
        return note;
    }

    private void ensureNotebookOwner(Long userId, Long notebookId) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        if (notebook == null) {
            throw new BizException(404, "笔记本不存在");
        }
        if (!Notebook.TYPE_COLLECTION.equals(notebook.getNodeType())) {
            throw new BizException("请选择资料库节点，领域/项目节点不能直接写入笔记");
        }
    }

    private String buildSearchText(String title, String summary, String content) {
        String compactContent = content == null ? "" : content.replaceAll("[#`>*_\\-\\[\\]()]", " ");
        return String.join(" ",
                StringUtils.hasText(title) ? title : "",
                StringUtils.hasText(summary) ? summary : "",
                compactContent
        );
    }
}
