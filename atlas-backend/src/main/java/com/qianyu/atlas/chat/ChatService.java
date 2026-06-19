package com.qianyu.atlas.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qianyu.atlas.rag.SearchService;
import com.qianyu.atlas.rag.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TOP_K = 5;

    private final SearchService searchService;
    private final ChatClientFactory chatClientFactory;
    private final Executor agentExecutor;
    private final com.qianyu.atlas.ai.AiTracer aiTracer;

    public ChatService(SearchService searchService,
                       ChatClientFactory chatClientFactory,
                       @Qualifier("agentExecutor") Executor agentExecutor,
                       com.qianyu.atlas.ai.AiTracer aiTracer) {
        this.searchService = searchService;
        this.chatClientFactory = chatClientFactory;
        this.agentExecutor = agentExecutor;
        this.aiTracer = aiTracer;
    }

    public ChatDtos.RagResponse rag(Long userId, ChatDtos.RagRequest request) {
        int topK = request.topK() == null ? DEFAULT_TOP_K : Math.min(Math.max(request.topK(), 1), 10);
        List<VectorSearchResult> chunks = searchService.retrieveHybridChunks(
                userId, request.question(), topK, request.notebookId());

        ChatClient client = chatClientFactory.current();
        String sys = systemPrompt();
        String usr = userPrompt(request.question(), chunks);
        long start = System.currentTimeMillis();
        String answer;
        boolean success = true;
        String error = null;
        try {
            answer = client.complete(List.of(
                    new ChatClient.Message("system", sys),
                    new ChatClient.Message("user", usr)
            ));
        } catch (RuntimeException ex) {
            success = false;
            error = ex.getMessage();
            try {
                aiTracer.record(new com.qianyu.atlas.ai.AiTracer.AiCall(
                        "rag", "chat", null, null, client.modelName(), client.providerId(), null,
                        System.currentTimeMillis() - start, false, error,
                        sys.length() + usr.length(), 0));
            } catch (Exception ignored) {}
            throw ex;
        }
        try {
            aiTracer.record(new com.qianyu.atlas.ai.AiTracer.AiCall(
                    "rag", "chat", null, null, client.modelName(), client.providerId(), null,
                    System.currentTimeMillis() - start, true, null,
                    sys.length() + usr.length(), answer == null ? 0 : answer.length()));
        } catch (Exception ignored) {}
        // 排干本次请求所有追踪(rag chat 本次 + 之前 search/embed 等),返回给前端
        return new ChatDtos.RagResponse(answer, toCitations(chunks), aiTracer.drain());
    }

    public SseEmitter ragStream(Long userId, ChatDtos.RagRequest request) {
        int topK = request.topK() == null ? DEFAULT_TOP_K : Math.min(Math.max(request.topK(), 1), 10);
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                List<VectorSearchResult> chunks = searchService.retrieveHybridChunks(
                        userId, request.question(), topK, request.notebookId());
                // 先发引用列表,前端可边等回答边展示来源
                try {
                    String citationsJson = MAPPER.writeValueAsString(toCitations(chunks));
                    emitter.send(SseEmitter.event().name("citations").data(citationsJson));
                } catch (Exception jsonException) {
                    log.debug("[ChatRAG] failed to send citations event, userId={}", userId, jsonException);
                }
                List<ChatClient.Message> messages = List.of(
                        new ChatClient.Message("system", systemPrompt()),
                        new ChatClient.Message("user", userPrompt(request.question(), chunks))
                );
                ChatClient streamClient = chatClientFactory.current();
                int inputChars = messages.stream().mapToInt(m -> m.content() == null ? 0 : m.content().length()).sum();
                long aiStart = System.currentTimeMillis();
                StringBuilder collected = new StringBuilder();
                // 流式 LLM 调用前先发 ai-call-start 事件,前端实时知道用了什么模型
                try {
                    String startJson = MAPPER.writeValueAsString(java.util.Map.of(
                            "scene", "rag",
                            "channel", "chat",
                            "model", streamClient.modelName(),
                            "providerId", streamClient.providerId(),
                            "inputChars", inputChars
                    ));
                    emitter.send(SseEmitter.event().name("ai-call-start").data(startJson));
                } catch (Exception ignored) {}
                try {
                    streamClient.completeStream(messages, delta -> {
                        collected.append(delta);
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(delta));
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    });
                    // 调用完成发 ai-call-end(成功)
                    try {
                        String endJson = MAPPER.writeValueAsString(java.util.Map.of(
                                "scene", "rag",
                                "success", true,
                                "durationMs", System.currentTimeMillis() - aiStart,
                                "outputChars", collected.length()
                        ));
                        emitter.send(SseEmitter.event().name("ai-call-end").data(endJson));
                    } catch (Exception ignored) {}
                } catch (RuntimeException streamEx) {
                    try {
                        String endJson = MAPPER.writeValueAsString(java.util.Map.of(
                                "scene", "rag",
                                "success", false,
                                "durationMs", System.currentTimeMillis() - aiStart,
                                "error", streamEx.getMessage() == null ? "" : streamEx.getMessage()
                        ));
                        emitter.send(SseEmitter.event().name("ai-call-end").data(endJson));
                    } catch (Exception ignored) {}
                    throw streamEx;
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(exception.getMessage()));
                } catch (Exception sendException) {
                    log.debug("[ChatRAG] failed to send SSE error event, userId={}", userId, sendException);
                }
                emitter.completeWithError(exception);
            }
        }, agentExecutor);
        return emitter;
    }

    private List<ChatDtos.Citation> toCitations(List<VectorSearchResult> chunks) {
        List<ChatDtos.Citation> citations = new ArrayList<>();
        for (VectorSearchResult chunk : chunks) {
            citations.add(new ChatDtos.Citation(
                    chunk.noteId(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    chunk.score()
            ));
        }
        return citations;
    }

    private String systemPrompt() {
        return """
                You are Atlas, a personal knowledge base assistant.
                Answer only according to the provided note excerpts.
                If the excerpts do not contain enough information, say that the current knowledge base has no reliable answer.
                Keep the answer concise, structured, and cite the note excerpt numbers when useful.
                """;
    }

    private String userPrompt(String question, List<VectorSearchResult> chunks) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n").append(question).append("\n\n");
        builder.append("Note excerpts:\n");
        if (chunks.isEmpty()) {
            builder.append("(No relevant excerpts were retrieved.)\n");
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                VectorSearchResult chunk = chunks.get(i);
                builder.append("[").append(i + 1).append("] ")
                        .append("noteId=").append(chunk.noteId())
                        .append(", chunkIndex=").append(chunk.chunkIndex())
                        .append(", score=").append(chunk.score())
                        .append("\n")
                        .append(clean(chunk.content()))
                        .append("\n\n");
            }
        }
        builder.append("Answer in Chinese unless the user asks for another language.");
        return builder.toString();
    }

    private String clean(String text) {
        if (!StringUtils.hasText(text)) return "";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 1800 ? cleaned.substring(0, 1800) + "..." : cleaned;
    }
}
