package com.qianyu.atlas.ai;

import com.qianyu.atlas.chat.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 给"裸调 ChatClient/EmbeddingClient"的场景加追踪。
 *
 * 设计原因:很多服务(LibraryService/PaperService/SearchTextSupport)直接
 * `chatClientFactory.current().complete(...)`,不走 AiAgentService,
 * 这些调用之前完全黑箱。用这个 helper 包一层就能统一追踪。
 *
 * 用法:
 *   String result = tracedChat.complete("paper-ai", client, messages);
 *   tracedChat.completeStream("note-agent-stream", client, messages, onDelta);
 */
@Component
public class TracedChatHelper {

    private final AiTracer aiTracer;

    public TracedChatHelper(AiTracer aiTracer) {
        this.aiTracer = aiTracer;
    }

    public String complete(String scene, ChatClient client, List<ChatClient.Message> messages) {
        long start = System.currentTimeMillis();
        int inputChars = totalChars(messages);
        boolean success = true;
        String error = null;
        String result = null;
        try {
            result = client.complete(messages);
            return result;
        } catch (RuntimeException ex) {
            success = false;
            error = ex.getMessage();
            throw ex;
        } finally {
            try {
                aiTracer.record(new AiTracer.AiCall(
                        scene, "chat", null, null,
                        client.modelName(), client.providerId(), null,
                        System.currentTimeMillis() - start,
                        success, error,
                        inputChars,
                        result == null ? 0 : result.length()
                ));
            } catch (Exception ignored) {}
        }
    }

    public void completeStream(String scene, ChatClient client,
                               List<ChatClient.Message> messages,
                               Consumer<String> onDelta) {
        long start = System.currentTimeMillis();
        int inputChars = totalChars(messages);
        StringBuilder collected = new StringBuilder();
        boolean success = true;
        String error = null;
        try {
            client.completeStream(messages, delta -> {
                collected.append(delta);
                onDelta.accept(delta);
            });
        } catch (RuntimeException ex) {
            success = false;
            error = ex.getMessage();
            throw ex;
        } finally {
            try {
                aiTracer.record(new AiTracer.AiCall(
                        scene, "chat", null, null,
                        client.modelName(), client.providerId(), null,
                        System.currentTimeMillis() - start,
                        success, error,
                        inputChars,
                        collected.length()
                ));
            } catch (Exception ignored) {}
        }
    }

    private int totalChars(List<ChatClient.Message> messages) {
        int n = 0;
        for (ChatClient.Message m : messages) {
            if (m.content() != null) n += m.content().length();
        }
        return n;
    }
}
