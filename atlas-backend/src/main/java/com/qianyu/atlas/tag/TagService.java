package com.qianyu.atlas.tag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import com.qianyu.atlas.tag.TagDtos.SaveTagRequest;
import com.qianyu.atlas.tag.TagDtos.SetNoteTagsRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TagService {
    private final TagMapper tagMapper;
    private final NoteTagMapper noteTagMapper;
    private final NoteMapper noteMapper;

    public TagService(TagMapper tagMapper, NoteTagMapper noteTagMapper, NoteMapper noteMapper) {
        this.tagMapper = tagMapper;
        this.noteTagMapper = noteTagMapper;
        this.noteMapper = noteMapper;
    }

    public Tag create(Long userId, SaveTagRequest request) {
        boolean exists = tagMapper.exists(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getUserId, userId)
                .eq(Tag::getName, request.name()));
        if (exists) {
            throw new BizException("标签已存在");
        }

        Tag tag = new Tag();
        tag.setUserId(userId);
        tag.setName(request.name());
        tag.setColor(request.color());
        tagMapper.insert(tag);
        return tag;
    }

    public List<Tag> listMine(Long userId) {
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getUserId, userId)
                .orderByAsc(Tag::getName));
    }

    @Transactional
    public List<Tag> ensureTags(Long userId, List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        Set<String> uniqueNames = new LinkedHashSet<>();
        for (String name : names) {
            String normalized = normalizeTagName(name);
            if (StringUtils.hasText(normalized)) uniqueNames.add(normalized);
        }
        if (uniqueNames.isEmpty()) return List.of();

        List<Tag> tags = new ArrayList<>();
        for (String name : uniqueNames) {
            Tag tag = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                    .eq(Tag::getUserId, userId)
                    .eq(Tag::getName, name)
                    .last("limit 1"));
            if (tag == null) {
                tag = new Tag();
                tag.setUserId(userId);
                tag.setName(name);
                tag.setColor(null);
                tagMapper.insert(tag);
            }
            tags.add(tag);
        }
        return tags;
    }

    @Transactional
    public List<Tag> setNoteTagsByName(Long userId, Long noteId, List<String> names) {
        List<Tag> tags = ensureTags(userId, names);
        return setNoteTags(userId, new SetNoteTagsRequest(
                noteId,
                tags.stream().map(Tag::getId).toList()
        ));
    }

    @Transactional
    public List<Tag> setNoteTags(Long userId, SetNoteTagsRequest request) {
        ensureNoteOwner(userId, request.noteId());

        noteTagMapper.delete(new LambdaQueryWrapper<NoteTag>()
                .eq(NoteTag::getUserId, userId)
                .eq(NoteTag::getNoteId, request.noteId()));

        List<Long> tagIds = request.tagIds() == null ? Collections.emptyList() : request.tagIds();
        for (Long tagId : tagIds) {
            ensureTagOwner(userId, tagId);
            NoteTag noteTag = new NoteTag();
            noteTag.setUserId(userId);
            noteTag.setNoteId(request.noteId());
            noteTag.setTagId(tagId);
            noteTagMapper.insert(noteTag);
        }
        return listByNote(userId, request.noteId());
    }

    public List<Tag> listByNote(Long userId, Long noteId) {
        ensureNoteOwner(userId, noteId);
        List<Long> tagIds = noteTagMapper.selectList(new LambdaQueryWrapper<NoteTag>()
                        .eq(NoteTag::getUserId, userId)
                        .eq(NoteTag::getNoteId, noteId))
                .stream()
                .map(NoteTag::getTagId)
                .toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getUserId, userId)
                .in(Tag::getId, tagIds)
                .orderByAsc(Tag::getName));
    }

    private void ensureNoteOwner(Long userId, Long noteId) {
        boolean exists = noteMapper.exists(new LambdaQueryWrapper<Note>()
                .eq(Note::getId, noteId)
                .eq(Note::getUserId, userId));
        if (!exists) {
            throw new BizException(404, "笔记不存在");
        }
    }

    private void ensureTagOwner(Long userId, Long tagId) {
        boolean exists = tagMapper.exists(new LambdaQueryWrapper<Tag>()
                .eq(Tag::getId, tagId)
                .eq(Tag::getUserId, userId));
        if (!exists) {
            throw new BizException(404, "标签不存在");
        }
    }

    private String normalizeTagName(String name) {
        if (name == null) return "";
        String normalized = name.replace("#", "")
                .trim()
                .replaceAll("\\s+", "-")
                .replaceAll("[\\r\\n\\t]", "");
        if (normalized.length() > 32) {
            normalized = normalized.substring(0, 32);
        }
        return normalized;
    }
}
