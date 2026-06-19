package com.qianyu.atlas.rag;

import com.qianyu.atlas.note.Note;
import com.qianyu.atlas.note.NoteMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchMatchService {
    private final NoteMapper noteMapper;

    public SearchMatchService(NoteMapper noteMapper) {
        this.noteMapper = noteMapper;
    }

    public List<SearchMatchDetail> matchDetails(Long userId, String query, Integer limitValue) {
        int limit = SearchTextSupport.detailLimit(limitValue);
        if (!StringUtils.hasText(query)) return List.of();

        List<Note> notes = noteMapper.search(userId, query, limit);
        List<SearchMatchDetail> details = new ArrayList<>(notes.size());
        for (Note note : notes) {
            List<SearchMatchExcerpt> excerpts = new ArrayList<>();
            excerpts.addAll(SearchTextSupport.matchExcerpts("title", "标题", note.getTitle(), query, 2));
            excerpts.addAll(SearchTextSupport.matchExcerpts("summary", "摘要", note.getSummary(), query, 2));
            excerpts.addAll(SearchTextSupport.matchExcerpts("content", "正文", note.getContent(), query, SearchTextSupport.EXCERPTS_PER_NOTE));
            if (excerpts.isEmpty()) continue;
            details.add(new SearchMatchDetail(
                    note.getId(),
                    note.getTitle(),
                    SearchTextSupport.countMatches(
                            SearchTextSupport.firstText(note.getTitle(), "")
                                    + "\n"
                                    + SearchTextSupport.firstText(note.getSummary(), "")
                                    + "\n"
                                    + SearchTextSupport.firstText(note.getContent(), ""),
                            query
                    ),
                    excerpts.size() > SearchTextSupport.EXCERPTS_PER_NOTE
                            ? excerpts.subList(0, SearchTextSupport.EXCERPTS_PER_NOTE)
                            : excerpts
            ));
        }
        return details;
    }
}
