package com.yupi.yuaiagent.tools;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 对话记忆检索元工具（模型自述"当前上下文找不到历史信息"时显式调用）
 * <p>
 * 与 searchTools 相同的元工具模式：每个 Agent 实例构造时自己 new、
 * 追加进 availableTools、不注册 Bean、不进 allTools、不进向量索引。
 * 检索触发不靠模型自觉（模型不知道自己不知道）——调用本身就是显式信号，
 * 日志里可观测、可审计：这轮它搜了记忆、命中了几条。
 */
@Slf4j
public class SearchMemoryTool {

    private final ConversationMemoryService conversationMemoryService;

    /**
     * 当前 Agent 的会话 ID 提供器。
     * 工具在构造期创建，而会话 ID 是构造后由 setter 设置的，
     * 所以不能构造时捕获，必须调用时再读（null 时降级为提示）。
     */
    private final Supplier<String> conversationIdProvider;

    private final AtomicInteger callCount = new AtomicInteger();

    private final AtomicInteger hitCount = new AtomicInteger();

    public SearchMemoryTool(ConversationMemoryService conversationMemoryService,
                            Supplier<String> conversationIdProvider) {
        this.conversationMemoryService = conversationMemoryService;
        this.conversationIdProvider = conversationIdProvider;
    }

    @Tool(name = "searchMemory", description = """
            Search this conversation's past messages stored in long-term memory.
            Use when the user refers to something mentioned earlier (possibly in a previous
            session) that you cannot recall from the current context, e.g. "上次说的那个建议"、
            "我前几轮提过的细节"。
            Returns the most relevant past exchanges as read-only reference material.
            """)
    public String searchMemory(
            @ToolParam(description = "Natural-language description of what you need to recall") String query) {
        callCount.incrementAndGet();
        if (conversationMemoryService == null) {
            return "对话记忆检索暂不可用（记忆服务未启用）。";
        }
        if (StrUtil.isBlank(query)) {
            return "用法：searchMemory(query) —— 描述你想回忆的历史信息，例如\"上次讨论的相亲注意事项\"。";
        }
        String chatId = conversationIdProvider.get();
        if (StrUtil.isBlank(chatId)) {
            return "当前会话未关联会话 ID，无法检索历史对话。";
        }
        String memory = conversationMemoryService.retrieveMemory(chatId, query);
        if (StrUtil.isBlank(memory)) {
            log.info("searchMemory 未命中: query={}", query);
            return "未检索到与「" + query + "」相关的历史对话。\n"
                    + "建议：1) 换更具体的关键词重试；"
                    + "2) 若你确信用户提到过，可以询问用户补充细节。";
        }
        hitCount.incrementAndGet();
        log.info("searchMemory 调用: query={}, 命中 {} 条", query, memory.lines().count());
        return "检索到以下历史对话（仅供参考，不要虚构其中未提到的细节）：\n" + memory;
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getHitCount() {
        return hitCount.get();
    }
}
