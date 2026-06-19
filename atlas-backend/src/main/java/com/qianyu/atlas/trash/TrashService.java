package com.qianyu.atlas.trash;

import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.library.LibraryItem;
import com.qianyu.atlas.library.LibraryItemMapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.paper.PaperAttachment;
import com.qianyu.atlas.paper.PaperAttachmentMapper;
import com.qianyu.atlas.paper.PaperStorageProperties;
import com.qianyu.atlas.rag.EmbeddingPipeline;
import com.qianyu.atlas.library.LibraryStorageProperties;
import com.qianyu.atlas.trash.TrashDtos.TrashItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class TrashService {
    private static final int RETENTION_DAYS = 30;

    private final NoteMapper noteMapper;
    private final LibraryItemMapper libraryItemMapper;
    private final PaperAttachmentMapper paperAttachmentMapper;
    private final EmbeddingPipeline embeddingPipeline;
    private final PaperStorageProperties paperStorageProperties;
    private final LibraryStorageProperties libraryStorageProperties;

    public TrashService(NoteMapper noteMapper,
                        LibraryItemMapper libraryItemMapper,
                        PaperAttachmentMapper paperAttachmentMapper,
                        EmbeddingPipeline embeddingPipeline,
                        PaperStorageProperties paperStorageProperties,
                        LibraryStorageProperties libraryStorageProperties) {
        this.noteMapper = noteMapper;
        this.libraryItemMapper = libraryItemMapper;
        this.paperAttachmentMapper = paperAttachmentMapper;
        this.embeddingPipeline = embeddingPipeline;
        this.paperStorageProperties = paperStorageProperties;
        this.libraryStorageProperties = libraryStorageProperties;
    }

    public List<TrashItem> list(Long userId) {
        List<TrashItem> items = new ArrayList<>();
        for (Note note : noteMapper.selectDeleted(userId)) {
            items.add(new TrashItem(
                    "note",
                    note.getId(),
                    note.getId(),
                    note.getNotebookId(),
                    note.getTitle(),
                    note.getSummary(),
                    text(note.getUpdatedAt()),
                    text(purgeAfter(note.getUpdatedAt()))
            ));
        }
        for (LibraryItem item : libraryItemMapper.selectDeleted(userId)) {
            items.add(new TrashItem(
                    "library",
                    item.getId(),
                    item.getNoteId(),
                    item.getNotebookId(),
                    item.getTitle(),
                    item.getOriginalFilename(),
                    text(item.getUpdatedAt()),
                    text(purgeAfter(item.getUpdatedAt()))
            ));
        }
        for (PaperAttachment paper : paperAttachmentMapper.selectDeleted(userId)) {
            items.add(new TrashItem(
                    "paper",
                    paper.getId(),
                    paper.getNoteId(),
                    paper.getNotebookId(),
                    paper.getOriginalFilename(),
                    "PDF 附件 · " + paper.getFileSize() + " bytes",
                    text(paper.getUpdatedAt()),
                    text(purgeAfter(paper.getUpdatedAt()))
            ));
        }
        items.sort(Comparator.comparing(TrashItem::deletedAt).reversed());
        return items;
    }

    public void restore(Long userId, String kind, Long id) {
        int changed = switch (normalizeKind(kind)) {
            case "note" -> noteMapper.restore(userId, id);
            case "library" -> libraryItemMapper.restore(userId, id);
            case "paper" -> paperAttachmentMapper.restore(userId, id);
            default -> 0;
        };
        if (changed == 0) throw new BizException(404, "回收站项目不存在或已恢复");
    }

    public void purge(Long userId, String kind, Long id) {
        String normalized = normalizeKind(kind);
        if ("note".equals(normalized)) {
            embeddingPipeline.deleteIndex(userId, id);
            int changed = noteMapper.purgeDeleted(userId, id);
            if (changed == 0) throw new BizException(404, "回收站笔记不存在");
            return;
        }
        if ("library".equals(normalized)) {
            LibraryItem item = libraryItemMapper.selectDeletedById(userId, id);
            if (item == null) {
                throw new BizException(404, "回收站资料不存在");
            }
            deletePhysicalFile(item.getUserId(), item.getStoragePath());
            int changed = libraryItemMapper.purgeDeleted(userId, id);
            if (changed == 0) throw new BizException(404, "回收站资料不存在");
            return;
        }
        if ("paper".equals(normalized)) {
            PaperAttachment paper = paperAttachmentMapper.selectDeletedById(userId, id);
            if (paper == null) {
                throw new BizException(404, "回收站 PDF 附件不存在");
            }
            deletePhysicalFile(paper.getUserId(), paper.getStoragePath());
            int changed = paperAttachmentMapper.purgeDeleted(userId, id);
            if (changed == 0) throw new BizException(404, "回收站 PDF 附件不存在");
        }
    }

    public int purgeExpired(Long userId) {
        int count = 0;
        LocalDateTime now = LocalDateTime.now();
        for (TrashItem item : list(userId)) {
            LocalDateTime purgeAfter = parsePurgeAfter(item);
            if (purgeAfter == null) continue;
            if (!purgeAfter.isAfter(now)) {
                purge(userId, item.kind(), item.id());
                count++;
            }
        }
        return count;
    }

    public int purgeAll(Long userId) {
        int count = 0;
        for (TrashItem item : new ArrayList<>(list(userId))) {
            purge(userId, item.kind(), item.id());
            count++;
        }
        return count;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void purgeExpiredAllUsers() {
        LocalDateTime now = LocalDateTime.now();
        for (Note note : noteMapper.selectAllDeleted()) {
            if (!purgeAfter(note.getUpdatedAt()).isAfter(now)) {
                embeddingPipeline.deleteIndex(note.getUserId(), note.getId());
                noteMapper.purgeDeleted(note.getUserId(), note.getId());
            }
        }
        for (LibraryItem item : libraryItemMapper.selectAllDeleted()) {
            if (!purgeAfter(item.getUpdatedAt()).isAfter(now)) {
                deletePhysicalFile(item.getUserId(), item.getStoragePath());
                libraryItemMapper.purgeDeleted(item.getUserId(), item.getId());
            }
        }
        for (PaperAttachment paper : paperAttachmentMapper.selectAllDeleted()) {
            if (!purgeAfter(paper.getUpdatedAt()).isAfter(now)) {
                deletePhysicalFile(paper.getUserId(), paper.getStoragePath());
                paperAttachmentMapper.purgeDeleted(paper.getUserId(), paper.getId());
            }
        }
    }

    private String normalizeKind(String kind) {
        if ("note".equals(kind) || "library".equals(kind) || "paper".equals(kind)) return kind;
        throw new BizException("回收站类型只能是 note / library / paper");
    }

    private LocalDateTime purgeAfter(LocalDateTime deletedAt) {
        return (deletedAt == null ? LocalDateTime.now() : deletedAt).plusDays(RETENTION_DAYS);
    }

    private String text(LocalDateTime time) {
        return (time == null ? LocalDateTime.now() : time).toString();
    }

    private void deletePhysicalFile(Long userId, String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return;
        try {
            Path path = Path.of(storagePath).toAbsolutePath().normalize();
            if (!insideAllowedRoot(userId, path)) {
                log.warn("[Trash] refuse to delete file outside owned storage root, userId={}, path={}", userId, path);
                return;
            }
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            log.warn("[Trash] failed to delete physical file, userId={}, path={}", userId, storagePath, exception);
        }
    }

    private LocalDateTime parsePurgeAfter(TrashItem item) {
        try {
            return LocalDateTime.parse(item.purgeAfter());
        } catch (Exception exception) {
            log.warn("[Trash] skip item with invalid purgeAfter, kind={}, id={}, purgeAfter={}",
                    item.kind(), item.id(), item.purgeAfter(), exception);
            return null;
        }
    }

    private boolean insideAllowedRoot(Long userId, Path path) {
        if (userId == null || path == null) return false;
        Path libraryRoot = Path.of(libraryStorageProperties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId)).normalize();
        Path paperRoot = Path.of(paperStorageProperties.getRoot()).toAbsolutePath().normalize().resolve(String.valueOf(userId)).normalize();
        return path.startsWith(libraryRoot) || path.startsWith(paperRoot);
    }
}
