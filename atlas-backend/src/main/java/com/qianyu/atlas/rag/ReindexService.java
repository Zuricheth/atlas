package com.qianyu.atlas.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReindexService {
    private static final Logger log = LoggerFactory.getLogger(ReindexService.class);
    private static final int PAGE_SIZE = 200;

    private final NoteMapper noteMapper;
    private final EmbeddingPipeline embeddingPipeline;

    public ReindexService(NoteMapper noteMapper, EmbeddingPipeline embeddingPipeline) {
        this.noteMapper = noteMapper;
        this.embeddingPipeline = embeddingPipeline;
    }

    @Async("embeddingExecutor")
    public void reindexAll() {
        Long total = noteMapper.selectCount(null);
        log.info("[RAG] global reindex start, total notes = {}", total);
        long processed = 0;
        long cursorId = 0L;
        while (true) {
            List<Note> page = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                    .gt(Note::getId, cursorId)
                    .orderByAsc(Note::getId)
                    .last("limit " + PAGE_SIZE));
            if (page.isEmpty()) break;
            for (Note note : page) {
                try {
                    embeddingPipeline.rebuildChunks(note.getUserId(), note.getId(), note.getTitle(), note.getContent());
                    embeddingPipeline.scheduleEmbedAfterCommit(note.getId());
                } catch (Exception ex) {
                    log.warn("[RAG] reindex failed noteId={} error={}", note.getId(), ex.getMessage());
                }
                cursorId = note.getId();
                processed++;
            }
        }
        log.info("[RAG] global reindex dispatched, processed = {}", processed);
    }

    @Async("embeddingExecutor")
    public void reindexUser(Long userId) {
        Long total = noteMapper.selectCount(new LambdaQueryWrapper<Note>().eq(Note::getUserId, userId));
        log.info("[RAG] reindex userId={} total notes={}", userId, total);
        long cursorId = 0L;
        while (true) {
            List<Note> page = noteMapper.selectList(new LambdaQueryWrapper<Note>()
                    .eq(Note::getUserId, userId)
                    .gt(Note::getId, cursorId)
                    .orderByAsc(Note::getId)
                    .last("limit " + PAGE_SIZE));
            if (page.isEmpty()) break;
            for (Note note : page) {
                embeddingPipeline.rebuildChunks(userId, note.getId(), note.getTitle(), note.getContent());
                embeddingPipeline.scheduleEmbedAfterCommit(note.getId());
                cursorId = note.getId();
            }
        }
    }
}
