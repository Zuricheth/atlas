package com.qianyu.atlas.notebook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.notebook.NotebookDtos.CreateNotebookRequest;
import com.qianyu.atlas.notebook.NotebookDtos.RenameNotebookRequest;
import com.qianyu.atlas.paper.PaperAttachment;
import com.qianyu.atlas.paper.PaperAttachmentMapper;
import com.qianyu.atlas.rag.EmbeddingPipeline;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class NotebookService {
    private final NotebookMapper notebookMapper;
    private final NoteMapper noteMapper;
    private final LibraryItemMapper libraryItemMapper;
    private final PaperAttachmentMapper paperAttachmentMapper;
    private final EmbeddingPipeline embeddingPipeline;
    private final NotebookService self;

    public NotebookService(NotebookMapper notebookMapper,
                           NoteMapper noteMapper,
                           LibraryItemMapper libraryItemMapper,
                           PaperAttachmentMapper paperAttachmentMapper,
                           EmbeddingPipeline embeddingPipeline,
                           @Lazy NotebookService self) {
        this.notebookMapper = notebookMapper;
        this.noteMapper = noteMapper;
        this.libraryItemMapper = libraryItemMapper;
        this.paperAttachmentMapper = paperAttachmentMapper;
        this.embeddingPipeline = embeddingPipeline;
        this.self = self;
    }

    public Notebook create(Long userId, CreateNotebookRequest request) {
        Notebook notebook = new Notebook();
        notebook.setUserId(userId);
        notebook.setName(request.name().trim());
        notebook.setDescription(request.description());
        notebook.setNodeType(normalizeNodeType(request.nodeType()));
        notebook.setParentId(validParentId(userId, request.parentId(), notebook.getNodeType()));
        notebook.setSortOrder(0);
        notebookMapper.insert(notebook);
        return notebook;
    }

    public List<Notebook> listMine(Long userId) {
        self.normalizeDuplicateTree(userId);
        return notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getDeleted, 0)
                .orderByAsc(Notebook::getParentId)
                .orderByAsc(Notebook::getSortOrder)
                .orderByAsc(Notebook::getNodeType)
                .orderByAsc(Notebook::getName)
                .orderByDesc(Notebook::getId));
    }

    public Notebook rename(Long userId, Long notebookId, RenameNotebookRequest request) {
        Notebook notebook = requireOwnedNotebook(userId, notebookId);
        notebook.setName(request.name().trim());
        notebook.setDescription(request.description());
        notebook.setUpdatedAt(LocalDateTime.now());
        notebookMapper.updateById(notebook);
        return notebookMapper.selectById(notebookId);
    }

    @Transactional
    public Notebook mergeInto(Long userId, Long sourceNotebookId, Long targetNotebookId) {
        if (targetNotebookId == null) {
            throw new BizException("请选择目标知识库");
        }
        if (sourceNotebookId.equals(targetNotebookId)) {
            throw new BizException("不能把知识库合并到自己");
        }
        Notebook source = requireOwnedNotebook(userId, sourceNotebookId);
        Notebook target = requireOwnedNotebook(userId, targetNotebookId);
        if (!Notebook.TYPE_COLLECTION.equals(target.getNodeType())) {
            if (wouldCreateCycle(userId, sourceNotebookId, targetNotebookId)) {
                throw new BizException("不能把上级知识库拖入自己的子级");
            }
            source.setParentId(targetNotebookId);
            source.setUpdatedAt(LocalDateTime.now());
            notebookMapper.updateById(source);
            target.setUpdatedAt(LocalDateTime.now());
            notebookMapper.updateById(target);
            return notebookMapper.selectById(sourceNotebookId);
        }
        if (!Notebook.TYPE_COLLECTION.equals(source.getNodeType())) {
            if (wouldCreateCycle(userId, sourceNotebookId, targetNotebookId)) {
                throw new BizException("不能把上级知识库拖入自己的子级");
            }
            source.setParentId(targetNotebookId);
            source.setUpdatedAt(LocalDateTime.now());
            notebookMapper.updateById(source);
            return notebookMapper.selectById(sourceNotebookId);
        }
        String subsetName = source.getName();
        LocalDateTime now = LocalDateTime.now();

        List<LibraryItem> items = libraryItemMapper.selectList(new LambdaQueryWrapper<LibraryItem>()
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNotebookId, sourceNotebookId)
                .eq(LibraryItem::getDeleted, 0));
        for (LibraryItem item : items) {
            item.setNotebookId(targetNotebookId);
            item.setCategory(prefixPath(subsetName, item.getCategory()));
            item.setUpdatedAt(now);
            libraryItemMapper.updateById(item);
        }

        paperAttachmentMapper.update(null, new LambdaUpdateWrapper<PaperAttachment>()
                .set(PaperAttachment::getNotebookId, targetNotebookId)
                .set(PaperAttachment::getUpdatedAt, now)
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNotebookId, sourceNotebookId)
                .eq(PaperAttachment::getDeleted, 0));

        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getNotebookId, sourceNotebookId)
                .eq(Note::getDeleted, 0));
        for (Note note : notes) {
            note.setNotebookId(targetNotebookId);
            note.setTitle(prefixPath(subsetName, note.getTitle()));
            note.setUpdatedAt(now);
            noteMapper.updateById(note);
            embeddingPipeline.rebuildChunks(userId, note.getId(), note.getTitle(), note.getContent());
            scheduleEmbeddingAfterCommit(note.getId());
        }

        notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                .set(Notebook::getDeleted, 1)
                .set(Notebook::getUpdatedAt, now)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getId, sourceNotebookId)
                .eq(Notebook::getDeleted, 0));

        target.setUpdatedAt(now);
        notebookMapper.updateById(target);
        return notebookMapper.selectById(targetNotebookId);
    }

    @Transactional
    public void delete(Long userId, Long notebookId) {
        Notebook notebook = requireOwnedNotebook(userId, notebookId);
        if (notebook == null) {
            throw new BizException(404, "知识库不存在");
        }
        for (Notebook child : childNotebooks(userId, notebookId)) {
            delete(userId, child.getId());
        }

        List<Note> notes = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                .eq(Note::getUserId, userId)
                .eq(Note::getNotebookId, notebookId));
        for (Note note : notes) {
            embeddingPipeline.deleteIndex(userId, note.getId());
            noteMapper.deleteById(note.getId());
        }

        libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                .set(LibraryItem::getDeleted, 1)
                .set(LibraryItem::getUpdatedAt, LocalDateTime.now())
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNotebookId, notebookId));

        paperAttachmentMapper.update(null, new LambdaUpdateWrapper<PaperAttachment>()
                .set(PaperAttachment::getDeleted, 1)
                .set(PaperAttachment::getUpdatedAt, LocalDateTime.now())
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNotebookId, notebookId));

        int changed = notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                .set(Notebook::getDeleted, 1)
                .set(Notebook::getUpdatedAt, LocalDateTime.now())
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getId, notebookId)
                .eq(Notebook::getDeleted, 0));
        if (changed == 0) {
            throw new BizException(404, "知识库不存在或已删除");
        }
    }

    @Transactional
    public synchronized Notebook ensureCollectionPath(Long userId,
                                                      String domainName,
                                                      String projectName,
                                                      String collectionName,
                                                      String description) {
        Notebook parent = null;
        if (StringUtils.hasText(domainName)) {
            parent = ensureNode(userId, null, Notebook.TYPE_DOMAIN, cleanName(domainName), "个人知识库领域");
        }
        if (StringUtils.hasText(projectName)) {
            parent = ensureNode(userId, parent == null ? null : parent.getId(), Notebook.TYPE_PROJECT, cleanName(projectName), "项目资料与长期主题");
        }
        String finalName = StringUtils.hasText(collectionName) ? collectionName : "资料库";
        return ensureNode(userId, parent == null ? null : parent.getId(), Notebook.TYPE_COLLECTION, cleanName(finalName), description);
    }

    public void ensureCollectionOwner(Long userId, Long notebookId) {
        Notebook notebook = requireOwnedNotebook(userId, notebookId);
        if (!Notebook.TYPE_COLLECTION.equals(notebook.getNodeType())) {
            throw new BizException("请选择资料库节点，领域/项目节点不能直接写入笔记");
        }
    }

    public List<Long> descendantNotebookIds(Long userId, Long notebookId) {
        requireOwnedNotebook(userId, notebookId);
        List<Long> ids = new ArrayList<>();
        collectDescendantNotebookIds(userId, notebookId, ids, new HashSet<>());
        return ids;
    }

    private void collectDescendantNotebookIds(Long userId, Long notebookId, List<Long> ids, Set<Long> visited) {
        if (notebookId == null || !visited.add(notebookId)) return;
        ids.add(notebookId);
        for (Notebook child : childNotebooks(userId, notebookId)) {
            collectDescendantNotebookIds(userId, child.getId(), ids, visited);
        }
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

    private synchronized Notebook ensureNode(Long userId, Long parentId, String nodeType, String name, String description) {
        Notebook notebook = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .isNull(parentId == null, Notebook::getParentId)
                .eq(parentId != null, Notebook::getParentId, parentId)
                .eq(Notebook::getNodeType, nodeType)
                .eq(Notebook::getName, name)
                .eq(Notebook::getDeleted, 0)
                .last("limit 1"));
        if (notebook != null) return notebook;
        notebook = new Notebook();
        notebook.setUserId(userId);
        notebook.setParentId(parentId);
        notebook.setNodeType(nodeType);
        notebook.setName(name);
        notebook.setDescription(description);
        notebook.setSortOrder(0);
        notebookMapper.insert(notebook);
        return notebook;
    }

    private void scheduleEmbeddingAfterCommit(Long noteId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    embeddingPipeline.embedAsync(noteId);
                }
            });
            return;
        }
        embeddingPipeline.embedAsync(noteId);
    }

    @Transactional
    public synchronized void normalizeDuplicateTree(Long userId) {
        boolean changed;
        int guard = 0;
        do {
            changed = false;
            List<Notebook> nodes = notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getDeleted, 0)
                    .orderByAsc(Notebook::getId));
            for (Notebook source : nodes) {
                Notebook target = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                        .eq(Notebook::getUserId, userId)
                        .isNull(source.getParentId() == null, Notebook::getParentId)
                        .eq(source.getParentId() != null, Notebook::getParentId, source.getParentId())
                        .eq(Notebook::getNodeType, source.getNodeType())
                        .eq(Notebook::getName, source.getName())
                        .eq(Notebook::getDeleted, 0)
                        .lt(Notebook::getId, source.getId())
                        .orderByAsc(Notebook::getId)
                        .last("limit 1"));
                if (target == null) continue;
                absorbDuplicateNotebook(userId, source, target);
                changed = true;
                break;
            }
        } while (changed && ++guard < 200);
    }

    private void absorbDuplicateNotebook(Long userId, Notebook source, Notebook target) {
        LocalDateTime now = LocalDateTime.now();
        notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                .set(Notebook::getParentId, target.getId())
                .set(Notebook::getUpdatedAt, now)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getParentId, source.getId())
                .eq(Notebook::getDeleted, 0));

        libraryItemMapper.update(null, new LambdaUpdateWrapper<LibraryItem>()
                .set(LibraryItem::getNotebookId, target.getId())
                .set(LibraryItem::getUpdatedAt, now)
                .eq(LibraryItem::getUserId, userId)
                .eq(LibraryItem::getNotebookId, source.getId())
                .eq(LibraryItem::getDeleted, 0));

        paperAttachmentMapper.update(null, new LambdaUpdateWrapper<PaperAttachment>()
                .set(PaperAttachment::getNotebookId, target.getId())
                .set(PaperAttachment::getUpdatedAt, now)
                .eq(PaperAttachment::getUserId, userId)
                .eq(PaperAttachment::getNotebookId, source.getId())
                .eq(PaperAttachment::getDeleted, 0));

        noteMapper.update(null, new LambdaUpdateWrapper<Note>()
                .set(Note::getNotebookId, target.getId())
                .set(Note::getUpdatedAt, now)
                .eq(Note::getUserId, userId)
                .eq(Note::getNotebookId, source.getId())
                .eq(Note::getDeleted, 0));

        notebookMapper.update(null, new LambdaUpdateWrapper<Notebook>()
                .set(Notebook::getDeleted, 1)
                .set(Notebook::getUpdatedAt, now)
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getId, source.getId())
                .eq(Notebook::getDeleted, 0));
    }

    private Long validParentId(Long userId, Long parentId, String nodeType) {
        if (parentId == null) return null;
        Notebook parent = requireOwnedNotebook(userId, parentId);
        if (Notebook.TYPE_DOMAIN.equals(nodeType)) {
            throw new BizException("领域必须是顶层节点");
        }
        if (Notebook.TYPE_PROJECT.equals(nodeType) && !Notebook.TYPE_DOMAIN.equals(parent.getNodeType())) {
            throw new BizException("项目只能放在领域下面");
        }
        return parentId;
    }

    private String normalizeNodeType(String value) {
        if (!StringUtils.hasText(value)) return Notebook.TYPE_COLLECTION;
        String type = value.trim().toLowerCase();
        if (List.of(Notebook.TYPE_DOMAIN, Notebook.TYPE_PROJECT, Notebook.TYPE_COLLECTION).contains(type)) {
            return type;
        }
        throw new BizException("知识库节点类型只能是 domain / project / collection");
    }

    private List<Notebook> childNotebooks(Long userId, Long parentId) {
        return notebookMapper.selectList(new LambdaQueryWrapper<Notebook>()
                .eq(Notebook::getUserId, userId)
                .eq(Notebook::getParentId, parentId)
                .eq(Notebook::getDeleted, 0));
    }

    private boolean wouldCreateCycle(Long userId, Long sourceId, Long targetId) {
        Long cursor = targetId;
        while (cursor != null) {
            if (Objects.equals(cursor, sourceId)) return true;
            Notebook node = notebookMapper.selectOne(new LambdaQueryWrapper<Notebook>()
                    .eq(Notebook::getUserId, userId)
                    .eq(Notebook::getId, cursor)
                    .eq(Notebook::getDeleted, 0)
                    .last("limit 1"));
            cursor = node == null ? null : node.getParentId();
        }
        return false;
    }

    private String cleanName(String value) {
        String clean = value == null ? "" : value.replace("/", "／").trim();
        return clean.isBlank() ? "未命名资料库" : clean;
    }

    private String prefixPath(String prefix, String value) {
        String cleanPrefix = prefix == null ? "子知识库" : prefix.replace("/", "／").trim();
        String cleanValue = StringUtils.hasText(value) ? value.trim() : "未分类";
        if (cleanValue.equals(cleanPrefix) || cleanValue.startsWith(cleanPrefix + "/")) return cleanValue;
        return cleanPrefix + "/" + cleanValue;
    }
}
