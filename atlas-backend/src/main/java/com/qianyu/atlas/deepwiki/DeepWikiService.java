package com.qianyu.atlas.deepwiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.ai.AiAgentService;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.deepwiki.DeepWikiDtos.GenerateWikiRequest;
import com.qianyu.atlas.deepwiki.DeepWikiDtos.GenerateWikiResponse;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.Notebook;
import com.qianyu.atlas.notebook.NotebookMapper;
import com.qianyu.atlas.notebook.NotebookService;
import com.qianyu.atlas.vcp.VcpProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Service
public class DeepWikiService {
    private static final int MAX_NOTES = 40;
    private static final int MAX_FILES = 80;
    private static final int MAX_NOTE_TEXT = 1800;
    private static final int MAX_FILE_TEXT = 1200;
    private static final int MAX_CONTEXT = 65_000;

    private final NotebookMapper notebookMapper;
    private final NotebookService notebookService;
    private final NoteMapper noteMapper;
    private final LibraryItemMapper libraryItemMapper;
    private final AiAgentService aiAgentService;
    private final VcpProperties vcpProperties;
    private final DeepWikiPageMapper pageMapper;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    public DeepWikiService(NotebookMapper notebookMapper,
                           NotebookService notebookService,
                           NoteMapper noteMapper,
                           LibraryItemMapper libraryItemMapper,
                           AiAgentService aiAgentService,
                           VcpProperties vcpProperties,
                           DeepWikiPageMapper pageMapper,
                           com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.notebookMapper = notebookMapper;
        this.notebookService = notebookService;
        this.noteMapper = noteMapper;
        this.libraryItemMapper = libraryItemMapper;
        this.aiAgentService = aiAgentService;
        this.vcpProperties = vcpProperties;
        this.pageMapper = pageMapper;
        this.aiTracer = aiTracer;
    }

    public GenerateWikiResponse generate(Long userId, GenerateWikiRequest request) {
        Notebook root = requireOwnedNotebook(userId, request.notebookId());
        String mode = normalizeMode(request.mode());
        String focus = StringUtils.hasText(request.focus()) ? request.focus().trim() : "";

        List<Long> notebookIds = notebookService.descendantNotebookIds(userId, root.getId());
        List<Note> notes = loadNotes(userId, notebookIds);
        List<LibraryItem> files = loadFiles(userId, notebookIds);
        if (notes.isEmpty() && files.isEmpty()) {
            throw new BizException("当前知识库还没有可生成 DeepWiki 的笔记或资料");
        }

        String context = buildContext(userId, root, notes, files);
        String markdown = aiAgentService.complete(
                request.agentId(),
                systemPrompt(),
                userPrompt(root, mode, focus, notes.size(), files.size(), context),
                "deepwiki"
        );
        markdown = normalizeMarkdown(markdown, root.getName(), mode, focus);
        GenerateWikiResponse saved = savePage(userId, root.getId(), request.agentId(), mode, focus, wikiTitle(root, mode, focus), notes.size() + files.size(), markdown);
        return saved.withAiTrace(aiTracer.drain());
    }

    public GenerateWikiResponse latest(Long userId, Long notebookId, String mode, String focus) {
        requireOwnedNotebook(userId, notebookId);
        DeepWikiPage page = pageMapper.selectOne(new LambdaQueryWrapper<DeepWikiPage>()
                .eq(DeepWikiPage::getUserId, userId)
                .eq(DeepWikiPage::getNotebookId, notebookId)
                .eq(DeepWikiPage::getMode, normalizeMode(mode))
                .eq(DeepWikiPage::getFocusKey, focusKey(focus))
                .last("limit 1"));
        if (page == null) {
            return null;
        }
        return toResponse(page, userId);
    }

    private GenerateWikiResponse savePage(Long userId,
                                          Long notebookId,
                                          Long agentId,
                                          String mode,
                                          String focus,
                                          String title,
                                          int sourceCount,
                                          String markdown) {
        String key = focusKey(focus);
        DeepWikiPage page = pageMapper.selectOne(new LambdaQueryWrapper<DeepWikiPage>()
                .eq(DeepWikiPage::getUserId, userId)
                .eq(DeepWikiPage::getNotebookId, notebookId)
                .eq(DeepWikiPage::getMode, mode)
                .eq(DeepWikiPage::getFocusKey, key)
                .last("limit 1"));
        LocalDateTime now = LocalDateTime.now();
        if (page == null) {
            page = new DeepWikiPage();
            page.setUserId(userId);
            page.setNotebookId(notebookId);
            page.setMode(mode);
            page.setFocusKey(key);
            page.setCreatedAt(now);
        }
        page.setAgentId(agentId);
        page.setFocus(StringUtils.hasText(focus) ? focus.trim() : "");
        page.setTitle(title);
        page.setSourceCount(sourceCount);
        page.setMarkdown(markdown);
        page.setUpdatedAt(now);
        if (page.getId() == null) pageMapper.insert(page);
        else pageMapper.updateById(page);
        return toResponse(pageMapper.selectById(page.getId()), userId);
    }

    private GenerateWikiResponse toResponse(DeepWikiPage page, Long userId) {
        return new GenerateWikiResponse(
                page.getNotebookId(),
                page.getTitle(),
                page.getMode(),
                page.getFocus(),
                page.getSourceCount(),
                page.getMarkdown(),
                page.getUpdatedAt() == null ? "" : page.getUpdatedAt().toString(),
                isStale(userId, page.getNotebookId(), page.getUpdatedAt())
        );
    }

    /**
     * 判断 DeepWiki 页是否过期：若知识库子树内笔记或资料文件的最近更新时间晚于页面更新时间，则视为 stale。
     */
    private boolean isStale(Long userId, Long notebookId, LocalDateTime pageUpdatedAt) {
        if (pageUpdatedAt == null) return false;
        List<Long> notebookIds = notebookService.descendantNotebookIds(userId, notebookId);
        if (notebookIds.isEmpty()) return false;

        LocalDateTime latestNote = latestUpdatedAt(
                noteMapper.selectList(new LambdaQueryWrapper<Note>()
                        .select(Note::getUpdatedAt)
                        .eq(Note::getUserId, userId)
                        .in(Note::getNotebookId, notebookIds)
                        .eq(Note::getDeleted, 0)
                        .orderByDesc(Note::getUpdatedAt)
                        .last("limit 1")),
                Note::getUpdatedAt);

        LocalDateTime latestFile = latestUpdatedAt(
                libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                        .select(LibraryItem::getUpdatedAt)
                        .eq(LibraryItem::getUserId, userId)
                        .in(LibraryItem::getNotebookId, notebookIds)
                        .eq(LibraryItem::getDeleted, 0)
                        .orderByDesc(LibraryItem::getUpdatedAt)
                        .last("limit 1")),
                LibraryItem::getUpdatedAt);

        return (latestNote != null && latestNote.isAfter(pageUpdatedAt))
                || (latestFile != null && latestFile.isAfter(pageUpdatedAt));
    }

    private <T> LocalDateTime latestUpdatedAt(List<T> rows, java.util.function.Function<T, LocalDateTime> extractor) {
        if (rows == null || rows.isEmpty()) return null;
        LocalDateTime latest = null;
        for (T row : rows) {
            LocalDateTime value = extractor.apply(row);
            if (value != null && (latest == null || value.isAfter(latest))) latest = value;
        }
        return latest;
    }

    private Notebook requireOwnedNotebook(Long userId, Long notebookId) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        if (notebook == null) {
            throw new BizException(404, "知识库不存在");
        }
        return notebook;
    }

    private List<Note> loadNotes(Long userId, List<Long> notebookIds) {
        if (notebookIds.isEmpty()) return List.of();
        return noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .in(Note::getNotebookId, notebookIds)
                .eq(Note::getDeleted, 0)
                .orderByDesc(Note::getUpdatedAt)
                .orderByDesc(Note::getId)
                .last("limit " + MAX_NOTES));
    }

    private List<LibraryItem> loadFiles(Long userId, List<Long> notebookIds) {
        if (notebookIds.isEmpty()) return List.of();
        return libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .in(LibraryItem::getNotebookId, notebookIds)
                .eq(LibraryItem::getDeleted, 0)
                .orderByDesc(LibraryItem::getUpdatedAt)
                .orderByDesc(LibraryItem::getId)
                .last("limit " + MAX_FILES));
    }

    private String buildContext(Long userId, Notebook root, List<Note> notes, List<LibraryItem> files) {
        Map<Long, Notebook> notebookById = notebookMap(userId);
        StringBuilder builder = new StringBuilder();
        builder.append("DeepWiki 根知识库：").append(notebookPath(notebookById, root)).append("\n");
        builder.append("根知识库描述：").append(firstText(root.getDescription(), "无")).append("\n\n");
        builder.append("## 当前知识库树\n");
        appendNotebookTree(builder, notebookById, root.getId(), 0);
        builder.append("\n");

        builder.append("## 笔记\n");
        for (Note note : notes) {
            appendBounded(builder, """
                    ### Note #%d: %s
                    来源链接：[Note #%d](atlas://note/%d)
                    路径：%s
                    摘要：%s
                    正文节选：
                    %s

                    """.formatted(
                    note.getId(),
                    firstText(note.getTitle(), "未命名笔记"),
                    note.getId(),
                    note.getId(),
                    notebookPath(notebookById, notebookById.get(note.getNotebookId())),
                    firstText(note.getSummary(), "无"),
                    trim(note.getContent(), MAX_NOTE_TEXT)
            ));
            if (builder.length() >= MAX_CONTEXT) return builder.substring(0, MAX_CONTEXT);
        }

        builder.append("\n## 资料文件\n");
        for (LibraryItem file : files) {
            appendBounded(builder, """
                    ### File #%d: %s
                    来源链接：[File #%d](atlas://library/%d)
                    路径：%s / %s
                    原文件：%s
                    类型：%s
                    关联笔记：%s
                    文本节选：
                    %s

                    """.formatted(
                    file.getId(),
                    firstText(file.getTitle(), file.getOriginalFilename(), "未命名资料"),
                    file.getId(),
                    file.getId(),
                    notebookPath(notebookById, notebookById.get(file.getNotebookId())),
                    firstText(file.getCategory(), "未分类"),
                    firstText(file.getOriginalFilename(), "无"),
                    firstText(file.getContentType(), file.getFileExt(), "未知"),
                    file.getNoteId(),
                    trim(file.getExtractedText(), MAX_FILE_TEXT)
            ));
            if (builder.length() >= MAX_CONTEXT) return builder.substring(0, MAX_CONTEXT);
        }
        return builder.toString();
    }

    private void appendNotebookTree(StringBuilder builder, Map<Long, Notebook> notebookById, Long parentId, int depth) {
        if (depth > 6 || builder.length() >= MAX_CONTEXT) return;
        List<Notebook> children = notebookById.values().stream()
                .filter(notebook -> parentId == null ? notebook.getParentId() == null : parentId.equals(notebook.getParentId()))
                .sorted((left, right) -> {
                    int sort = Integer.compare(left.getSortOrder() == null ? 0 : left.getSortOrder(), right.getSortOrder() == null ? 0 : right.getSortOrder());
                    return sort != 0 ? sort : firstText(left.getName()).compareTo(firstText(right.getName()));
                })
                .toList();
        for (Notebook child : children) {
            appendBounded(builder, "%s- %s：%s\n".formatted("  ".repeat(depth), firstText(child.getName(), "未命名知识库"), firstText(child.getDescription(), "无描述")));
            appendNotebookTree(builder, notebookById, child.getId(), depth + 1);
        }
    }

    private Map<Long, Notebook> notebookMap(Long userId) {
        List<Notebook> notebooks = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0));
        Map<Long, Notebook> map = new HashMap<>();
        for (Notebook notebook : notebooks) map.put(notebook.getId(), notebook);
        return map;
    }

    private String notebookPath(Map<Long, Notebook> notebooks, Notebook notebook) {
        if (notebook == null) return "未知知识库";
        List<String> names = new ArrayList<>();
        Notebook cursor = notebook;
        int guard = 0;
        while (cursor != null && guard++ < 12) {
            names.add(0, cursor.getName());
            cursor = cursor.getParentId() == null ? null : notebooks.get(cursor.getParentId());
        }
        return String.join(" / ", names);
    }

    private String systemPrompt() {
        return """
                你是 Atlas DeepWiki Agent。你的任务是把个人知识库资料整理成中文用户友好的 DeepWiki 认知层。
                输出必须是 Markdown，不要包裹代码块，不要声称已经写入数据库或调用工具。
                必须基于提供的笔记和文件节选写；不确定的内容要标注“当前资料未明确说明”。
                页面要像高质量文档站，而不是聊天回答：先给全景，再给路径，再给模块关系，再允许追问，最后把判断钉回来源。
                所有重要判断都要给来源链接，优先使用上下文中的 [Note #id](atlas://note/id) 或 [File #id](atlas://library/id)。
                不要堆砌文件名；要把来源抽象成主题、模块、概念和阅读路径。
                Mermaid 图必须使用简单 graph TD 或 graph LR，节点名使用中文，节点数量控制在 6-14 个。
                认知地图里对应到具体来源笔记/文件的关键节点，要用 mermaid 原生 click 语句标注跳转链接，
                格式：`click 节点ID href "atlas://note/笔记ID"` 或 `click 节点ID href "atlas://library/文件ID"`。
                只给最重要的 3-6 个节点加 click，其余节点保持纯结构。链接必须是上下文中真实存在的 [Note #id](atlas://note/id) 或 [File #id](atlas://library/id)。

                记忆管理系统。
                {{VarDailyNoteGuide}}

                你可以写入自己的 Agent 工作日记，用来记住 DeepWiki 页面结构、用户偏好、已采用的目录方案和下次改进点。
                这些是 Agent 工作记忆，不是用户知识同步。不要把未确认的用户知识写入用户知识日记本。

                %s
                %s
                """.formatted(firstText(vcpProperties.getDeepwikiWorkDsl(), ""), firstText(vcpProperties.getPublicMemoryDsl(), ""));
    }

    private String userPrompt(Notebook root,
                              String mode,
                              String focus,
                              int noteCount,
                              int fileCount,
                              String context) {
        String modeInstruction = switch (mode) {
            case "topic" -> "生成一个专题 Wiki 页面，聚焦主题：“" + focus + "”。";
            case "map" -> "生成一个知识地图页面，强调主题结构、概念关系和阅读路径。";
            default -> "生成这个知识库的 DeepWiki 首页。";
        };
        return """
                %s

                目标知识库：%s
                资料规模：%d 篇笔记，%d 个资料文件。

                请严格使用以下结构：
                # 页面标题

                > 认知摘要：用一句话说明这个知识库/专题在解决什么问题，并附 1-3 个关键来源链接。

                ## 一、全景总览
                用中文写 3-5 句话。先讲“这是什么”，再讲“为什么值得看”，最后讲“当前资料边界”。重要判断必须带来源链接。

                ## 二、认知地图
                先给 1 段解释，然后输出 Mermaid 图。对最重要的来源节点，用 click 语句标注跳转：
                ```mermaid
                graph TD
                    A[根主题] --> B[子主题]
                    B[核心方法] --> C[具体实现]
                    click B href "atlas://note/123"
                    click C href "atlas://library/456"
                ```
                节点数量控制在 6-14 个，只给 3-6 个关键节点加 click，链接用上下文里真实存在的 [Note #id](atlas://note/id) 或 [File #id](atlas://library/id)。

                ## 三、知识结构
                用分层列表组织主要主题。每个一级主题都要说明：它是什么、看哪些来源、适合追问什么。

                ## 四、关键概念
                用 Markdown 表格列出：概念｜一句话解释｜相关来源｜可追问方向。

                ## 五、推荐阅读路径
                给出 3-6 步阅读顺序，每一步说明为什么先看它，并附来源链接。

                ## 六、可继续追问
                给出 6 个适合继续问 Atlas 的中文问题。问题要具体，不要泛泛地问“总结一下”。

                ## 七、来源索引
                列出用到的 Note/File 链接，说明每个来源贡献了什么。不要列未使用来源。

                知识库上下文：
                %s
                """.formatted(modeInstruction, root.getName(), noteCount, fileCount, context);
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) return "home";
        String normalized = mode.trim().toLowerCase();
        if (List.of("home", "topic", "map").contains(normalized)) return normalized;
        return "home";
    }

    private String focusKey(String focus) {
        return StringUtils.hasText(focus) ? focus.trim().toLowerCase().replaceAll("\\s+", " ") : "";
    }

    private String normalizeMarkdown(String markdown, String rootName, String mode, String focus) {
        String clean = markdown == null ? "" : markdown.trim()
                .replaceAll("(?is)^```(?:markdown|md)?\\s*", "")
                .replaceAll("(?is)```\\s*$", "")
                .trim();
        if (!StringUtils.hasText(clean)) {
            clean = "# " + wikiTitle(rootName, mode, focus) + "\n\n当前 Agent 没有返回可用内容。";
        }
        if (!clean.startsWith("# ")) {
            clean = "# " + wikiTitle(rootName, mode, focus) + "\n\n" + clean;
        }
        return clean;
    }

    private String wikiTitle(Notebook notebook, String mode, String focus) {
        return wikiTitle(notebook.getName(), mode, focus);
    }

    private String wikiTitle(String rootName, String mode, String focus) {
        if ("topic".equals(mode) && StringUtils.hasText(focus)) return focus + " - DeepWiki";
        if ("map".equals(mode)) return rootName + " 知识地图";
        return rootName + " DeepWiki";
    }

    private void appendBounded(StringBuilder builder, String text) {
        if (builder.length() >= MAX_CONTEXT) return;
        int remaining = MAX_CONTEXT - builder.length();
        builder.append(text, 0, Math.min(text.length(), remaining));
    }

    private String trim(String text, int max) {
        if (!StringUtils.hasText(text)) return "无";
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() > max ? clean.substring(0, max) + "..." : clean;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }
}
