package com.qianyu.atlas.rag;

import com.qianyu.atlas.note.Note;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTextSupportTest {
    @Test
    void clampsSearchLimits() {
        assertThat(SearchTextSupport.topK(null)).isEqualTo(10);
        assertThat(SearchTextSupport.topK(0)).isEqualTo(1);
        assertThat(SearchTextSupport.topK(999)).isEqualTo(50);

        assertThat(SearchTextSupport.detailLimit(null)).isEqualTo(30);
        assertThat(SearchTextSupport.detailLimit(999)).isEqualTo(100);

        assertThat(SearchTextSupport.treeLimit(1)).isEqualTo(10);
        assertThat(SearchTextSupport.treeLimit(999)).isEqualTo(300);
    }

    @Test
    void extractsStableQueryTerms() {
        assertThat(SearchTextSupport.queryTerms(SearchTextSupport.normalize("  vcp 记忆/同步，日记本  ")))
                .containsExactly("vcp", "记忆", "同步", "日记本", "vcp 记忆/同步，日记本");
    }

    @Test
    void buildsMatchExcerptWithOffsets() {
        List<SearchMatchExcerpt> excerpts = SearchTextSupport.matchExcerpts(
                "content",
                "正文",
                "这是第一段。VCP 记忆同步中心需要支持批量转移和语义搜索。",
                "记忆同步",
                3
        );

        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).field()).isEqualTo("content");
        assertThat(excerpts.get(0).text()).contains("VCP 记忆同步中心");
        assertThat(excerpts.get(0).matchEnd()).isGreaterThan(excerpts.get(0).matchStart());
    }

    @Test
    void scoresExactTitleAndRecentNotesHigher() {
        Note note = new Note();
        note.setTitle("VCP 记忆同步中心");
        note.setSummary("管理日记本和文件转移");
        note.setContent("支持语义搜索、批量删除和批量转移。");
        note.setUpdatedAt(LocalDateTime.now());

        SearchTextSupport.HybridScore score = SearchTextSupport.hybridScore(
                note,
                "Atlas/VCP",
                "vcp 记忆同步中心",
                SearchTextSupport.queryTerms("vcp 记忆同步中心"),
                null
        );

        assertThat(score.value()).isGreaterThan(20f);
        assertThat(score.source()).isEqualTo("hybrid-exact");
    }
}
