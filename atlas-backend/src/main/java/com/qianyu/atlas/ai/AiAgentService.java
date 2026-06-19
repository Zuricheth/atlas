package com.qianyu.atlas.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qianyu.atlas.ai.AiAdminDtos.SaveAgentRequest;
import com.qianyu.atlas.chat.ChatClient;
import com.qianyu.atlas.chat.ChatClientFactory;
import com.qianyu.atlas.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

@Service
public class AiAgentService {
    private final AiAgentMapper agentMapper;
    private final AiModelMapper modelMapper;
    private final ChatClientFactory chatClientFactory;
    private final AiTracer aiTracer;

    public AiAgentService(AiAgentMapper agentMapper,
                          AiModelMapper modelMapper,
                          ChatClientFactory chatClientFactory,
                          AiTracer aiTracer) {
        this.agentMapper = agentMapper;
        this.modelMapper = modelMapper;
        this.chatClientFactory = chatClientFactory;
        this.aiTracer = aiTracer;
    }

    public List<AiAgent> list() {
        return agentMapper.selectList(new LambdaQueryWrapper<AiAgent>()
                .orderByDesc(AiAgent::getIsDefault)
                .orderByAsc(AiAgent::getId));
    }

    public List<AiAgent> ensureAtlasSystemAgents() {
        AiModel model = modelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getKind, AiModel.KIND_CHAT)
                .eq(AiModel::getEnabled, 1)
                .orderByAsc(AiModel::getId)
                .last("limit 1"));
        if (model == null) {
            throw new BizException("请先在 AI 设置里配置可用 Chat 模型，再创建 Atlas 系统 Agent");
        }
        ensureNamedAgent("Atlas DeepWiki Agent", model.getId(), deepWikiPrompt(), "Atlas DeepWiki Agent 日记本", false);
        ensureNamedAgent("Atlas VCP Sync Agent", model.getId(), vcpSyncPrompt(), "Atlas VCP Sync Agent 日记本", false);
        return list();
    }

    public AiAgent save(SaveAgentRequest request) {
        AiModel model = modelMapper.selectById(request.modelId());
        if (model == null || !AiModel.KIND_CHAT.equals(model.getKind())) {
            throw new BizException("Agent 必须选择 Chat 模型");
        }

        AiAgent agent = request.id() == null ? new AiAgent() : agentMapper.selectById(request.id());
        if (agent == null) throw new BizException(404, "Agent 不存在");
        agent.setName(request.name().trim());
        agent.setModelId(request.modelId());
        agent.setSystemPrompt(request.systemPrompt().trim());
        agent.setVcpFolder(request.vcpFolder());
        agent.setEnabled(request.enabled() == null || request.enabled() ? 1 : 0);
        agent.setIsDefault(request.isDefault() != null && request.isDefault() ? 1 : 0);
        agent.setUpdatedAt(LocalDateTime.now());

        if (agent.getIsDefault() == 1) {
            agentMapper.update(null, new LambdaUpdateWrapper<AiAgent>().set(AiAgent::getIsDefault, 0));
        }
        if (agent.getId() == null) agentMapper.insert(agent);
        else agentMapper.updateById(agent);
        return agentMapper.selectById(agent.getId());
    }

    private void ensureNamedAgent(String name, Long modelId, String prompt, String vcpFolder, boolean isDefault) {
        AiAgent existing = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getName, name)
                .last("limit 1"));
        AiAgent agent = existing == null ? new AiAgent() : existing;
        agent.setName(name);
        agent.setModelId(modelId);
        agent.setSystemPrompt(prompt);
        agent.setVcpFolder(vcpFolder);
        agent.setEnabled(1);
        agent.setIsDefault(isDefault ? 1 : 0);
        agent.setUpdatedAt(LocalDateTime.now());
        if (agent.getId() == null) agentMapper.insert(agent);
        else agentMapper.updateById(agent);
    }

    private String deepWikiPrompt() {
        return """
                你是 Atlas DeepWiki Agent。你负责把 Atlas 知识库和 VCP 长期记忆整理成可浏览、可维护的 Wiki 页面。

                记忆管理系统。
                {{VarDailyNoteGuide}}

                你可以写入自己的 Agent 工作日记，用来记住用户偏好、Wiki 结构、页面生成决策和下次改进点。
                这些是你的工作记忆，不是用户知识同步；不要把未确认的用户知识写入用户知识日记本。

                输出要求：
                - 默认输出 Markdown
                - 保持来源意识，区分 Atlas 来源和 VCP 记忆
                - 不确定的内容标注“当前资料未明确说明”
                - 页面要像 Wiki 文档，不要像聊天回答
                """;
    }

    private String vcpSyncPrompt() {
        return """
                你是 Atlas VCP Sync Agent。你负责审查 Atlas 生成的 VCP_AI_MEMORY 草稿，并给出同步、合并、忽略、补 Tag 和复核建议。

                记忆管理系统。
                {{VarDailyNoteGuide}}

                你可以写入自己的 Agent 工作日记，用来记住同步决策、用户偏好、重复判断规则和下次改进点。
                不要把未确认的用户知识直接写入用户知识日记本；用户知识同步必须由 Atlas VCP 同步中心确认。

                输出要求：
                - 默认输出 Markdown
                - 每条草稿给出目标日记本、理由和处理建议
                - 优先保护 VCP 记忆质量，低质量内容建议复核或忽略
                """;
    }

    public void delete(Long id) {
        agentMapper.deleteById(id);
    }

    public AiAgent requireUsable(Long id) {
        AiAgent agent = id == null ? defaultAgent() : agentMapper.selectById(id);
        if (agent == null || agent.getEnabled() == null || agent.getEnabled() == 0) {
            throw new BizException("请先在 AI 设置里配置可用 Agent");
        }
        return agent;
    }

    public String complete(Long agentId, String fallbackSystemPrompt, String userPrompt) {
        return complete(agentId, fallbackSystemPrompt, userPrompt, "agent");
    }

    /** 带场景标记的 complete,用于 AI 追踪(scene 例如 inbox-plan / deepwiki / note-agent / paper-ai) */
    public String complete(Long agentId, String fallbackSystemPrompt, String userPrompt, String scene) {
        AiAgent agent = requireUsable(agentId);
        String systemPrompt = StringUtils.hasText(agent.getSystemPrompt()) ? agent.getSystemPrompt() : fallbackSystemPrompt;
        ChatClient client = chatClientFactory.forModel(agent.getModelId());
        return traceCompletion(scene, agent, client, systemPrompt, userPrompt,
                () -> client.complete(List.of(
                        new ChatClient.Message("system", systemPrompt),
                        new ChatClient.Message("user", userPrompt)
                )));
    }

    public String completeWithSystemOverride(Long agentId, String systemPrompt, String userPrompt) {
        return completeWithSystemOverride(agentId, systemPrompt, userPrompt, "agent-override");
    }

    public String completeWithSystemOverride(Long agentId, String systemPrompt, String userPrompt, String scene) {
        AiAgent agent = requireUsable(agentId);
        ChatClient client = chatClientFactory.forModel(agent.getModelId());
        return traceCompletion(scene, agent, client, systemPrompt, userPrompt,
                () -> client.complete(List.of(
                        new ChatClient.Message("system", systemPrompt),
                        new ChatClient.Message("user", userPrompt)
                )));
    }

    public void completeStream(Long agentId, String fallbackSystemPrompt, String userPrompt, Consumer<String> onDelta) {
        completeStream(agentId, fallbackSystemPrompt, userPrompt, "agent-stream", onDelta);
    }

    public void completeStream(Long agentId, String fallbackSystemPrompt, String userPrompt, String scene, Consumer<String> onDelta) {
        AiAgent agent = requireUsable(agentId);
        String systemPrompt = StringUtils.hasText(agent.getSystemPrompt()) ? agent.getSystemPrompt() : fallbackSystemPrompt;
        ChatClient client = chatClientFactory.forModel(agent.getModelId());
        long start = System.currentTimeMillis();
        StringBuilder collected = new StringBuilder();
        boolean[] success = { true };
        String[] error = { null };
        try {
            client.completeStream(List.of(
                    new ChatClient.Message("system", systemPrompt),
                    new ChatClient.Message("user", userPrompt)
            ), delta -> {
                collected.append(delta);
                onDelta.accept(delta);
            });
        } catch (RuntimeException ex) {
            success[0] = false;
            error[0] = ex.getMessage();
            throw ex;
        } finally {
            recordTrace(scene, agent, client, systemPrompt.length() + userPrompt.length(),
                    collected.length(), System.currentTimeMillis() - start, success[0], error[0]);
        }
    }

    /** 包裹一次同步 LLM 调用,自动记录 trace */
    private String traceCompletion(String scene, AiAgent agent, ChatClient client,
                                   String systemPrompt, String userPrompt,
                                   java.util.function.Supplier<String> work) {
        long start = System.currentTimeMillis();
        boolean success = true;
        String error = null;
        String result = null;
        try {
            result = work.get();
            return result;
        } catch (RuntimeException ex) {
            success = false;
            error = ex.getMessage();
            throw ex;
        } finally {
            recordTrace(scene, agent, client, systemPrompt.length() + userPrompt.length(),
                    result == null ? 0 : result.length(),
                    System.currentTimeMillis() - start, success, error);
        }
    }

    private void recordTrace(String scene, AiAgent agent, ChatClient client,
                             int inputChars, int outputChars,
                             long durationMs, boolean success, String error) {
        try {
            aiTracer.record(new AiTracer.AiCall(
                    scene,
                    "chat",
                    agent == null ? null : agent.getId(),
                    agent == null ? null : agent.getName(),
                    client.modelName(),
                    client.providerId(),
                    null,
                    durationMs,
                    success,
                    error,
                    inputChars,
                    outputChars
            ));
        } catch (Exception ignored) {
            // 追踪失败不影响业务
        }
    }

    private AiAgent defaultAgent() {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getEnabled, 1)
                .eq(AiAgent::getIsDefault, 1)
                .orderByAsc(AiAgent::getId)
                .last("limit 1"));
        if (agent != null) return agent;
        return agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getEnabled, 1)
                .orderByAsc(AiAgent::getId)
                .last("limit 1"));
    }
}
