package com.qianyu.atlas.vcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.ai.AiAgentService;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.vcp.VcpDtos.BatchSuggestRequest;
import com.qianyu.atlas.vcp.VcpDtos.BatchSuggestResponse;
import com.qianyu.atlas.vcp.VcpDtos.DraftView;
import com.qianyu.atlas.vcp.VcpDtos.NotebookFileView;
import com.qianyu.atlas.vcp.VcpDtos.NotebookView;
import com.qianyu.atlas.vcp.VcpDtos.SuggestDraftTargetResponse;
import com.qianyu.atlas.vcp.VcpDtos.SyncDraftResponse;
import com.qianyu.atlas.vcp.VcpDtos.TransferDraftsRequest;
import com.qianyu.atlas.vcp.VcpDtos.TransferResult;
import com.qianyu.atlas.vcp.VcpDtos.UpdateDraftRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class VcpMemoryDraftService {
    private static final Pattern VCP_MEMORY_PATTERN = Pattern.compile(
            "<<<\\s*VCP_AI_MEMORY\\s*>>>([\\s\\S]*?)<<<\\s*END_VCP_AI_MEMORY\\s*>>>",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final VcpMemoryDraftMapper draftMapper;
    private final VcpProperties properties;
    private final VcpNotebookService notebookService;
    private final AiAgentService aiAgentService;

    public VcpMemoryDraftService(VcpMemoryDraftMapper draftMapper,
                                 VcpProperties properties,
                                 VcpNotebookService notebookService,
                                 AiAgentService aiAgentService) {
        this.draftMapper = draftMapper;
        this.properties = properties;
        this.notebookService = notebookService;
        this.aiAgentService = aiAgentService;
    }

    public void captureFromAgentNote(Note note, String agentContent) {
        if (note == null || !StringUtils.hasText(agentContent)) return;
        Matcher matcher = VCP_MEMORY_PATTERN.matcher(agentContent);
        while (matcher.find()) {
            String memory = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!StringUtils.hasText(memory)) continue;
            VcpMemoryDraft draft = new VcpMemoryDraft();
            draft.setUserId(note.getUserId());
            draft.setNoteId(note.getId());
            draft.setNotebookId(note.getNotebookId());
            draft.setTitle(note.getTitle());
            draft.setMemoryContent(memory);
            draft.setSuggestedDailyNote(suggestNotebook(note, memory));
            draft.setTargetDailyNote(draft.getSuggestedDailyNote());
            draft.setStatus(qualityOk(memory) ? VcpMemoryDraft.STATUS_PENDING : VcpMemoryDraft.STATUS_REVIEW);
            draft.setCreatedAt(LocalDateTime.now());
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.insert(draft);
        }
    }

    public List<DraftView> list(Long userId, String status) {
        LambdaQueryWrapper<VcpMemoryDraft> wrapper = new LambdaQueryWrapper<VcpMemoryDraft>()
                .eq(VcpMemoryDraft::getUserId, userId)
                .eq(StringUtils.hasText(status), VcpMemoryDraft::getStatus, status)
                .orderByDesc(VcpMemoryDraft::getUpdatedAt)
                .orderByDesc(VcpMemoryDraft::getId);
        return draftMapper.selectList(wrapper).stream().map(this::toView).toList();
    }

    public DraftView update(Long userId, Long draftId, UpdateDraftRequest request) {
        VcpMemoryDraft draft = requireOwnedDraft(userId, draftId);
        if (StringUtils.hasText(request.targetDailyNote())) {
            draft.setTargetDailyNote(notebookService.sanitizeName(request.targetDailyNote()));
        }
        if (StringUtils.hasText(request.memoryContent())) {
            draft.setMemoryContent(request.memoryContent().trim());
        }
        if (StringUtils.hasText(request.status())) {
            draft.setStatus(normalizeStatus(request.status()));
        }
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
        return toView(draftMapper.selectById(draftId));
    }

    public SyncDraftResponse sync(Long userId, Long draftId, String targetDailyNote) {
        VcpMemoryDraft draft = requireOwnedDraft(userId, draftId);
        String notebook = StringUtils.hasText(targetDailyNote)
                ? notebookService.sanitizeName(targetDailyNote)
                : StringUtils.hasText(draft.getTargetDailyNote())
                ? notebookService.sanitizeName(draft.getTargetDailyNote())
                : notebookService.sanitizeName(properties.getDefaultTargetNotebook());
        notebookService.createNotebook(userId, notebook);

        String filename = "draft-" + draft.getId()
                + "_note-" + draft.getNoteId()
                + "_" + notebookService.sanitizeFilename(draft.getTitle());
        Path file = notebookService.filePath(userId, notebook, filename);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, renderDraftFile(draft, notebook), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            draft.setStatus(VcpMemoryDraft.STATUS_FAILED);
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.updateById(draft);
            throw new UncheckedIOException("同步 VCP 日记失败", exception);
        }

        draft.setTargetDailyNote(notebook);
        draft.setSyncedPath(file.toString());
        draft.setStatus(VcpMemoryDraft.STATUS_SYNCED);
        draft.setUpdatedAt(LocalDateTime.now());
        draftMapper.updateById(draft);
        return new SyncDraftResponse(draft.getId(), notebook, file.getFileName().toString(), file.toString());
    }

    public BatchSuggestResponse suggest(Long userId, BatchSuggestRequest request) {
        if (request.draftIds() == null || request.draftIds().isEmpty()) {
            throw new BizException("请选择要分析的草稿");
        }
        List<VcpMemoryDraft> drafts = draftMapper.selectList(new LambdaQueryWrapper<VcpMemoryDraft>()
                .eq(VcpMemoryDraft::getUserId, userId)
                .in(VcpMemoryDraft::getId, request.draftIds())
                .orderByAsc(VcpMemoryDraft::getId));
        if (drafts.isEmpty()) throw new BizException("没有找到可分析的草稿");
        String suggestion = aiAgentService.complete(
                request.agentId(),
                syncAgentSystemPrompt(),
                syncAgentUserPrompt(drafts)
        );
        return new BatchSuggestResponse(suggestion == null ? "" : suggestion.trim());
    }

    public SuggestDraftTargetResponse suggestTarget(Long userId, Long draftId, Long agentId) {
        VcpMemoryDraft draft = requireOwnedDraft(userId, draftId);
        List<NotebookView> notebooks = safeNotebooks(userId);
        String suggestion = aiAgentService.complete(
                agentId,
                syncAgentSystemPrompt(),
                singleTargetUserPrompt(draft, notebooks)
        );
        String target = extractTargetNotebook(suggestion, notebooks);
        if (StringUtils.hasText(target)) {
            draft.setSuggestedDailyNote(target);
            draft.setTargetDailyNote(target);
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.updateById(draft);
        }
        return new SuggestDraftTargetResponse(toView(draftMapper.selectById(draftId)), suggestion == null ? "" : suggestion.trim());
    }

    public TransferResult transfer(Long userId, TransferDraftsRequest request) {
        if (request.draftIds() == null || request.draftIds().isEmpty()) {
            throw new BizException("请选择要转移的记忆草稿");
        }
        String targetNotebook = notebookService.sanitizeName(request.targetNotebook());
        notebookService.createNotebook(userId, targetNotebook);
        List<VcpMemoryDraft> drafts = draftMapper.selectList(new LambdaQueryWrapper<VcpMemoryDraft>()
                .eq(VcpMemoryDraft::getUserId, userId)
                .in(VcpMemoryDraft::getId, request.draftIds())
                .orderByAsc(VcpMemoryDraft::getId));
        if (drafts.isEmpty()) throw new BizException("没有找到可转移的记忆草稿");

        List<String> messages = new ArrayList<>();
        int moved = 0;
        int skipped = Math.max(0, request.draftIds().size() - drafts.size());
        for (VcpMemoryDraft draft : drafts) {
            draft.setTargetDailyNote(targetNotebook);
            if (request.moveSyncedFiles() && StringUtils.hasText(draft.getSyncedPath())) {
                try {
                    Path movedFile = notebookService.transferExistingFile(userId, Path.of(draft.getSyncedPath()), targetNotebook, request.overwrite());
                    draft.setSyncedPath(movedFile.toString());
                    messages.add("Draft #" + draft.getId() + " 已移动同步文件到 " + movedFile.getFileName());
                } catch (RuntimeException exception) {
                    skipped++;
                    messages.add("Draft #" + draft.getId() + " 文件移动失败：" + exception.getMessage());
                }
            } else {
                messages.add("Draft #" + draft.getId() + " 目标已改为 " + targetNotebook);
            }
            draft.setUpdatedAt(LocalDateTime.now());
            draftMapper.updateById(draft);
            moved++;
        }
        return new TransferResult("", targetNotebook, moved, skipped, messages);
    }

    public void delete(Long userId, Long draftId) {
        VcpMemoryDraft draft = requireOwnedDraft(userId, draftId);
        draftMapper.deleteById(draft.getId());
    }

    private VcpMemoryDraft requireOwnedDraft(Long userId, Long draftId) {
        VcpMemoryDraft draft = draftMapper.selectOne(new LambdaQueryWrapper<VcpMemoryDraft>()
                .eq(VcpMemoryDraft::getId, draftId)
                .eq(VcpMemoryDraft::getUserId, userId)
                .last("limit 1"));
        if (draft == null) throw new BizException(404, "VCP 记忆草稿不存在");
        return draft;
    }

    private String suggestNotebook(Note note, String memory) {
        List<NotebookView> notebooks = safeNotebooks(note == null ? null : note.getUserId());
        if (!notebooks.isEmpty()) {
            String query = String.join("\n",
                    note == null ? "" : firstText(note.getTitle(), ""),
                    note == null ? "" : firstText(note.getSummary(), ""),
                    memory
            );
            NotebookMatch best = bestNotebookMatch(note == null ? null : note.getUserId(), query, notebooks);
            if (best != null && best.score() >= 9) {
                return best.name();
            }
        }
        String lower = memory.toLowerCase(Locale.ROOT);
        if (lower.contains("atlas") || lower.contains("rag") || lower.contains("embedding")) return "Atlas知识库日记本";
        String generated = suggestNewNotebookName(note, memory);
        if (StringUtils.hasText(generated)) return generated;
        String configured = properties.getDefaultTargetNotebook();
        if (StringUtils.hasText(configured)) return configured.trim();
        return "默认知识日记本";
    }

    private boolean qualityOk(String memory) {
        if (memory.length() < 80) return false;
        return memory.toLowerCase(Locale.ROOT).contains("tag:");
    }

    private String normalizeStatus(String status) {
        String value = status.trim().toLowerCase(Locale.ROOT);
        if (List.of(
                VcpMemoryDraft.STATUS_PENDING,
                VcpMemoryDraft.STATUS_REVIEW,
                VcpMemoryDraft.STATUS_SYNCED,
                VcpMemoryDraft.STATUS_IGNORED,
                VcpMemoryDraft.STATUS_FAILED
        ).contains(value)) {
            return value;
        }
        throw new BizException("草稿状态只能是 pending / review / synced / ignored / failed");
    }

    private String renderDraftFile(VcpMemoryDraft draft, String notebook) {
        return """
                # %s

                Source: Atlas
                TargetDailyNote: %s
                AtlasNoteId: %d
                AtlasNotebookId: %d
                SyncedAt: %s

                ## Memory

                %s
                """.formatted(
                StringUtils.hasText(draft.getTitle()) ? draft.getTitle().trim() : "Atlas VCP Memory",
                notebook,
                draft.getNoteId(),
                draft.getNotebookId(),
                LocalDateTime.now(),
                draft.getMemoryContent().trim()
        );
    }

    private String syncAgentSystemPrompt() {
        return """
                你是 Atlas VCP Sync Agent，负责审查 Atlas 生成的 VCP_AI_MEMORY 草稿，并给出批量同步建议。

                记忆管理系统。
                {{VarDailyNoteGuide}}

                你可以写入自己的 Agent 工作日记，用来记住同步决策、用户偏好、重复判断规则和下次改进点。
                不要把未确认的用户知识直接写入用户知识日记本；用户知识同步必须由 Atlas VCP 同步中心确认。

                %s

                输出 Markdown，必须包含：
                1. 批量结论
                2. 每条草稿的目标日记本建议
                3. 是否需要合并、忽略、补 Tag 或人工复核
                4. 可直接应用的处理清单
                """.formatted(firstText(properties.getSyncWorkDsl(), ""));
    }

    private String syncAgentUserPrompt(List<VcpMemoryDraft> drafts) {
        StringBuilder builder = new StringBuilder();
        builder.append("请审查下面的待同步 VCP 记忆草稿：\n\n");
        for (VcpMemoryDraft draft : drafts) {
            builder.append("## Draft #").append(draft.getId()).append("\n")
                    .append("Title: ").append(draft.getTitle()).append("\n")
                    .append("SuggestedDailyNote: ").append(draft.getSuggestedDailyNote()).append("\n")
                    .append("Status: ").append(draft.getStatus()).append("\n")
                    .append("Memory:\n").append(draft.getMemoryContent()).append("\n\n");
        }
        return builder.toString();
    }

    private String singleTargetUserPrompt(VcpMemoryDraft draft, List<NotebookView> notebooks) {
        StringBuilder builder = new StringBuilder();
        builder.append("请为下面这条 VCP 记忆草稿选择最合适的目标日记本。\n")
                .append("要求：优先从候选日记本中选择已有日记本；如果没有语义上特别符合的候选，可以直接给出一个新的中文日记本名称，Atlas 会在同步时创建它。\n")
                .append("不要选择或建议以“簇”结尾的文件夹，这类目录是聚类/整理目录，不是 VCP 日记本。\n")
                .append("输出必须先给一行：目标日记本: <名称>\n")
                .append("然后用 2-4 句说明理由。\n\n")
                .append("候选日记本：\n");
        if (notebooks.isEmpty()) {
            builder.append("- 暂无可用候选。请根据草稿语义新建一个合适的中文日记本名称。\n");
        } else {
            for (NotebookView notebook : notebooks) {
                builder.append("- ").append(notebook.name())
                        .append("（").append(notebook.fileCount()).append(" 个文件，更新 ")
                        .append(notebook.lastModified()).append("）\n");
            }
        }
        builder.append("\n草稿标题：").append(draft.getTitle()).append("\n")
                .append("当前建议：").append(draft.getSuggestedDailyNote()).append("\n")
                .append("当前目标：").append(draft.getTargetDailyNote()).append("\n")
                .append("记忆内容：\n").append(trimForAgent(draft.getMemoryContent(), 10_000));
        return builder.toString();
    }

    private String extractTargetNotebook(String suggestion, List<NotebookView> notebooks) {
        if (!StringUtils.hasText(suggestion)) return "";
        String text = suggestion.trim();
        Matcher matcher = Pattern.compile("目标日记本\\s*[:：]\\s*([^\\n\\r]+)").matcher(text);
        String raw = matcher.find() ? matcher.group(1).trim() : firstLine(text);
        raw = cleanTargetNotebookName(raw);
        if (!StringUtils.hasText(raw)) return "";
        for (NotebookView notebook : notebooks) {
            if (raw.equalsIgnoreCase(notebook.name()) || raw.contains(notebook.name())) {
                return notebook.name();
            }
        }
        String rawLower = raw.toLowerCase(Locale.ROOT);
        for (NotebookView notebook : notebooks) {
            if (notebook.name().toLowerCase(Locale.ROOT).contains(rawLower)) {
                return notebook.name();
            }
        }
        return notebookService.sanitizeName(raw);
    }

    private String cleanTargetNotebookName(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String clean = raw.replaceAll("[`\"'“”‘’\\[\\]]", "").trim();
        clean = clean.replaceAll("(?i)^(目标日记本|target)\\s*[:：]\\s*", "").trim();
        clean = clean.replaceAll("[（(]\\s*(新建|创建|建议新建|new)\\s*[）)]", "").trim();
        clean = clean.replaceAll("\\s*(新建|创建)$", "").trim();
        clean = clean.replaceAll("[。；;，,].*$", "").trim();
        if (clean.endsWith("簇")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        return clean;
    }

    private String firstLine(String text) {
        String[] lines = text.split("\\R");
        return lines.length == 0 ? "" : lines[0].trim();
    }

    private List<NotebookView> safeNotebooks(Long userId) {
        try {
            return notebookService.listNotebooks(userId);
        } catch (Exception exception) {
            log.warn("[VCP] failed to load notebooks for draft suggestion, userId={}", userId, exception);
            return List.of();
        }
    }

    private NotebookMatch bestNotebookMatch(Long userId, String query, List<NotebookView> notebooks) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) return null;
        NotebookMatch best = null;
        for (NotebookView notebook : notebooks) {
            String profile = notebookProfile(userId, notebook);
            Set<String> profileTerms = terms(profile);
            int score = 0;
            String normalizedQuery = normalize(query);
            String normalizedName = normalize(notebook.name());
            if (normalizedQuery.contains(normalizedName)) score += 80;
            for (String term : terms(notebook.name())) {
                if (queryTerms.contains(term)) score += term.length() >= 3 ? 10 : 5;
            }
            for (String term : queryTerms) {
                if (profileTerms.contains(term)) score += term.length() >= 3 ? 3 : 1;
            }
            if (notebook.fileCount() != null && notebook.fileCount() > 0) score += 1;
            if (best == null || score > best.score()) {
                best = new NotebookMatch(notebook.name(), score);
            }
        }
        return best;
    }

    private String notebookProfile(Long userId, NotebookView notebook) {
        StringBuilder builder = new StringBuilder(notebook.name()).append("\n");
        try {
            List<NotebookFileView> files = notebookService.listFiles(userId, notebook.name());
            int count = 0;
            for (NotebookFileView file : files) {
                builder.append(file.filename()).append("\n");
                if (count < 2) {
                    try {
                        builder.append(trimForAgent(notebookService.readFile(userId, file.notebook(), file.filename()).content(), 1800)).append("\n");
                    } catch (Exception exception) {
                        log.debug("[VCP] skip unreadable notebook profile file, userId={}, notebook={}, file={}",
                                userId, file.notebook(), file.filename(), exception);
                    }
                }
                count++;
                if (count >= 8) break;
            }
        } catch (Exception exception) {
            log.debug("[VCP] failed to build notebook profile, userId={}, notebook={}", userId, notebook.name(), exception);
        }
        return builder.toString();
    }

    private Set<String> terms(String text) {
        String normalized = normalize(text);
        Set<String> result = new HashSet<>();
        Matcher matcher = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]{2,}").matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                addChineseTerms(result, token);
            } else if (!stopWords().contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private void addChineseTerms(Set<String> result, String token) {
        if (token.length() >= 2) result.add(token);
        for (int size : List.of(2, 3, 4)) {
            for (int i = 0; i + size <= token.length(); i++) {
                String part = token.substring(i, i + size);
                if (!stopWords().contains(part)) result.add(part);
            }
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[`*_#>\\[\\](){}<>\"'“”‘’｜|，。！？；：、/\\\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> stopWords() {
        return Set.of("内容", "当前", "建议", "目标", "日记", "日记本", "知识", "记忆", "草稿", "同步", "文件", "标题", "the", "and", "for", "with");
    }

    private String suggestNewNotebookName(Note note, String memory) {
        String title = note == null ? "" : firstText(note.getTitle(), "");
        String tagBased = firstUsefulTag(memory);
        String base = StringUtils.hasText(title) ? title : tagBased;
        if (!StringUtils.hasText(base)) return "";
        base = base.replaceAll("\\.[a-zA-Z0-9]{1,8}$", "")
                .replaceAll("^\\d{4}[-_年]\\d{1,2}[-_月]\\d{1,2}[^\\p{IsHan}a-zA-Z0-9]*", "")
                .replaceAll("[\\[\\]【】()（）《》\"'“”‘’]", " ")
                .replaceAll("(官方|定盘|补全版|最终版|草稿|笔记|资料|文档|文件)$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (!StringUtils.hasText(base)) base = tagBased;
        if (!StringUtils.hasText(base)) return "";
        if (base.length() > 32) {
            base = base.substring(0, 32).trim();
        }
        if (base.endsWith("簇")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        if (!base.endsWith("日记本") && !base.endsWith("知识") && !base.endsWith("设定集")) {
            base = base + "日记本";
        }
        return notebookService.sanitizeName(base);
    }

    private String firstUsefulTag(String memory) {
        if (!StringUtils.hasText(memory)) return "";
        Matcher matcher = Pattern.compile("(?im)^\\s*Tag\\s*[:：]\\s*(.+)$").matcher(memory);
        if (!matcher.find()) return "";
        String[] tags = matcher.group(1).split("[,，、#\\s]+");
        for (String tag : tags) {
            String clean = tag.trim();
            if (clean.length() >= 2 && !stopWords().contains(clean)) return clean;
        }
        return "";
    }

    private DraftView toView(VcpMemoryDraft draft) {
        return new DraftView(
                draft.getId(),
                draft.getNoteId(),
                draft.getNotebookId(),
                draft.getTitle(),
                draft.getMemoryContent(),
                draft.getSuggestedDailyNote(),
                draft.getTargetDailyNote(),
                draft.getStatus(),
                draft.getSyncedPath(),
                draft.getCreatedAt() == null ? "" : draft.getCreatedAt().toString(),
                draft.getUpdatedAt() == null ? "" : draft.getUpdatedAt().toString()
        );
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String trimForAgent(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        String clean = text.trim();
        return clean.length() > max ? clean.substring(0, max) + "\n\n[内容过长，已截断]" : clean;
    }

    private record NotebookMatch(String name, int score) {
    }
}
