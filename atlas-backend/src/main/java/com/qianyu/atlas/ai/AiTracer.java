package com.qianyu.atlas.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 调用追踪器:按 HTTP 请求收集本次涉及的所有 AI 调用,
 * 让前端/用户能看到"这一步用了什么模型/agent/耗时"。
 *
 * 设计:
 * - 用 ThreadLocal + request-scoped 收集(同一请求内所有 AI 调用归一组)
 * - 异步线程(@Async)的调用不自动继承;如需追踪,调用方手动开 scope
 * - 收集失败不影响业务(追踪是辅助)
 */
@Component
public class AiTracer {

    private static final ThreadLocal<List<AiCall>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

    /** 记录一次 AI 调用。所有参数容错,永不抛异常。 */
    public void record(AiCall call) {
        try {
            CURRENT.get().add(call);
        } catch (Exception ignored) {
            // 追踪失败不影响业务
        }
    }

    /** 拿当前请求已收集的 AI 调用(返回副本)。 */
    public List<AiCall> drain() {
        try {
            List<AiCall> calls = CURRENT.get();
            if (calls == null || calls.isEmpty()) return Collections.emptyList();
            List<AiCall> copy = new ArrayList<>(calls);
            calls.clear();
            return copy;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /** 是否处于请求上下文(用于判断是否自动收集)。 */
    public static boolean inRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    public record AiCall(
            String scene,        // 场景:rag / inbox-plan / deepwiki / library-auto / note-agent / paper-ai / embed / search-semantic
            String channel,      // chat / embedding
            Long agentId,        // 走的 agent(可空,空表示走 active model)
            String agentName,    // agent 名(可空)
            String model,        // 模型名
            Long providerId,     // 渠道 id(可空)
            String providerName, // 渠道名(可空)
            long durationMs,     // 耗时
            boolean success,     // 成功否
            String error,        // 失败原因(可空)
            int inputChars,      // 输入字符数(粗估)
            int outputChars      // 输出字符数(粗估)
    ) {
    }
}
