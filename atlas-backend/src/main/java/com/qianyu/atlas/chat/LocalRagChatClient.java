package com.qianyu.atlas.chat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 RAG 问答兜底实现。
 * 当未配置真实 Chat Provider 时启用，保证 MVP 可以在无外部 API Key 的情况下跑通：
 * 登录 -> 建笔记 -> 保存触发切片/向量化 -> 检索 -> 基于召回片段生成一个可演示答案。
 */
public class LocalRagChatClient implements ChatClient {
    private static final Pattern QUESTION_PATTERN = Pattern.compile("Question:\\s*\\R([\\s\\S]*?)\\R\\RNote excerpts:", Pattern.MULTILINE);
    private static final Pattern EXCERPT_PATTERN = Pattern.compile("\\[(\\d+)]\\s+noteId=([^,]+),\\s+chunkIndex=([^,]+),\\s+score=([^\\n]+)\\n([\\s\\S]*?)(?=\\n\\n\\[\\d+]|\\n\\nAnswer in Chinese|$)");

    @Override
    public String complete(List<Message> messages) {
        String prompt = messages == null || messages.isEmpty()
                ? ""
                : messages.get(messages.size() - 1).content();

        String question = extractQuestion(prompt);
        if (prompt == null || prompt.contains("(No relevant excerpts were retrieved.)")) {
            return "当前知识库中没有检索到足够相关的内容，暂时无法给出可靠答案。";
        }

        Matcher matcher = EXCERPT_PATTERN.matcher(prompt == null ? "" : prompt);
        StringBuilder answer = new StringBuilder();
        answer.append("根据当前知识库的检索结果");

        if (!question.isBlank()) {
            answer.append("，针对“").append(trim(question, 80)).append("”");
        }

        answer.append("，可以得到以下回答：\n\n");

        int count = 0;
        while (matcher.find() && count < 3) {
            count++;
            String index = matcher.group(1);
            String content = clean(matcher.group(5));
            if (content.isBlank()) continue;
            answer.append(count).append(". ")
                    .append(trim(content, count == 1 ? 260 : 180))
                    .append("（来源片段 ").append(index).append("）\n");
        }

        if (count == 0) {
            return "当前知识库中没有检索到足够相关的内容，暂时无法给出可靠答案。";
        }

        answer.append("\n说明：当前使用的是本地 MVP 兜底回答器；配置真实 Chat 模型后，可切换为大模型生成更自然的回答。");
        return answer.toString();
    }

    @Override
    public String modelName() {
        return "local-rag-fallback";
    }

    @Override
    public Long providerId() {
        return null;
    }

    private String extractQuestion(String prompt) {
        if (prompt == null) return "";
        Matcher matcher = QUESTION_PATTERN.matcher(prompt);
        if (!matcher.find()) return "";
        return clean(matcher.group(1));
    }

    private String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private String trim(String text, int maxLen) {
        String cleaned = clean(text);
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen) + "...";
    }
}