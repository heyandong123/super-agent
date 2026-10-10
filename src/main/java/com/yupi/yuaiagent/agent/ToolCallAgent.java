//package com.yupi.yuaiagent.agent;
//
//import cn.hutool.core.collection.CollUtil;
//import cn.hutool.core.util.StrUtil;
//import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
//import com.yupi.yuaiagent.agent.model.AgentState;
//import lombok.Data;
//import lombok.EqualsAndHashCode;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.chat.messages.AssistantMessage;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.messages.ToolResponseMessage;
//import org.springframework.ai.chat.messages.UserMessage;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.prompt.ChatOptions;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.model.tool.ToolCallingManager;
//import org.springframework.ai.model.tool.ToolExecutionResult;
//import org.springframework.ai.tool.ToolCallback;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
///**
// * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
// */
//@EqualsAndHashCode(callSuper = true)
//@Data
//@Slf4j
//public class ToolCallAgent extends ReActAgent {
//
//    // 可用的工具
//    private final ToolCallback[] availableTools;
//
//    // 保存工具调用信息的响应结果（要调用那些工具）
//    private ChatResponse toolCallChatResponse;
//
//    // 工具调用管理者
//    private final ToolCallingManager toolCallingManager;
//
//    // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
//    private final ChatOptions chatOptions;
//
//    public ToolCallAgent(ToolCallback[] availableTools) {
//        super();
//        this.availableTools = availableTools;
//        this.toolCallingManager = ToolCallingManager.builder().build();
//        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
//        this.chatOptions = DashScopeChatOptions.builder()
//                .withInternalToolExecutionEnabled(false)
//                .build();
//    }
//
//    /**
//     * 处理当前状态并决定下一步行动
//     *
//     * @return 是否需要执行行动
//     */
//    @Override
//    public boolean think() {
//        // 1、校验提示词，拼接用户提示词
//        if (StrUtil.isNotBlank(getNextStepPrompt())) {
//            UserMessage userMessage = new UserMessage(getNextStepPrompt());
//            getMessageList().add(userMessage);
//        }
//        // 2、调用 AI 大模型，获取工具调用结果
//        List<Message> messageList = getMessageList();
//        Prompt prompt = new Prompt(messageList, this.chatOptions);
//        try {
//            ChatResponse chatResponse = getChatClient().prompt(prompt)
//                    .system(getSystemPrompt())
//                    .tools(availableTools)
//                    .call()
//                    .chatResponse();
//            // 记录响应，用于等下 Act
//            this.toolCallChatResponse = chatResponse;
//            // 3、解析工具调用结果，获取要调用的工具
//            // 助手消息
//            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
//            // 获取要调用的工具列表
//            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
//            // 输出提示信息
//            String result = assistantMessage.getText();
//            log.info(getName() + "的思考：" + result);
//            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
//            String toolCallInfo = toolCallList.stream()
//                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
//                    .collect(Collectors.joining("\n"));
//            log.info(toolCallInfo);
//            // 如果不需要调用工具，返回 false
//            if (toolCallList.isEmpty()) {
//                // 只有不调用工具时，才需要手动记录助手消息
//                getMessageList().add(assistantMessage);
//                return false;
//            } else {
//                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
//                return true;
//            }
//        } catch (Exception e) {
//            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
//            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
//            return false;
//        }
//    }
//
//    /**
//     * 执行工具调用并处理结果
//     *
//     * @return 执行结果
//     */
//    @Override
//    public String act() {
//        if (!toolCallChatResponse.hasToolCalls()) {
//            return "没有工具需要调用";
//        }
//        // 调用工具
//        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
//        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
//        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
//        setMessageList(toolExecutionResult.conversationHistory());
//        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
//        // 判断是否调用了终止工具
//        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
//                .anyMatch(response -> response.name().equals("doTerminate"));
//        if (terminateToolCalled) {
//            // 任务结束，更改状态
//            setState(AgentState.FINISHED);
//        }
//        String results = toolResponseMessage.getResponses().stream()
//                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
//                .collect(Collectors.joining("\n"));
//        log.info(results);
//        return results;
//    }
//}
package com.yupi.yuaiagent.agent;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;
import java.util.stream.Collectors;
/**
 * 处理工具调用的基础代理类（将 ReAct 模式真正落地）
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {
    // 定义该 Agent 能够使用的武器库（比如文件读写、联网搜索）
    private final ToolCallback[] availableTools;
    // 记忆切片：用于在 think() 和 act() 之间传递大模型的工具调用指令
    private ChatResponse toolCallChatResponse;
    // Spring AI 提供的官方工具执行管家
    private final ToolCallingManager toolCallingManager;
    // 专门用于覆写 Spring AI 默认行为的配置项
    private final ChatOptions chatOptions;
    public ToolCallAgent(ToolCallback[] availableTools) {
        super();
        this.availableTools = availableTools;
        // 初始化工具管家
        this.toolCallingManager = ToolCallingManager.builder().build();
        // TODO 1: 禁用内置的自动工具调用，接管控制权
        // 提示：使用 DashScopeChatOptions.builder().withInternalToolExecutionEnabled(false).build();
        this.chatOptions = DashScopeChatOptions
                .builder()
                .withInternalToolExecutionEnabled(false)
                .build();// 替换此处代码
    }
//    @Override
//    public boolean think() {
//        // TODO 2: 如果配置了每步固定提示词，将其作为 UserMessage 塞入记忆列表
//        // 提示：检查 StrUtil.isNotBlank(getNextStepPrompt())，如果非空，new 一个 UserMessage 加入 getMessageList()。
//        if(StrUtil.isNotBlank(getNextStepPrompt())){
//            getMessageList().add(new UserMessage(getNextStepPrompt()));
//        }
//        List<Message> messageList = getMessageList();
//        // TODO 3: 构建包含完整历史记忆和禁用了自动调用的配置 Prompt
//        Prompt prompt = new Prompt(messageList, this.chatOptions);
//        try {
//            // TODO 4: 向大模型发起 HTTP 请求
//            // 提示：链式调用 getChatClient().prompt(prompt).system(getSystemPrompt()).tools(availableTools).call().chatResponse();
//            ChatResponse chatResponse = getChatClient()
//                    .prompt(prompt)
//                    .system(getSystemPrompt())
//                    .tools(availableTools)
//                    .call()
//                    .chatResponse(); // 替换此处代码
//            // TODO 5: 将大模型的回复存入全局变量，留给稍后的 act() 方法使用
//            this.toolCallChatResponse = chatResponse;
//            // TODO 6: 从返回体中剥离出助手消息和工具调用列表
//            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
//            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
//            // TODO 7: 打印日志（记录大模型说了什么文本，打算用几个工具）
//            String result = assistantMessage.getText();
//            log.info(getName() + "的思考：" + result);
//            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
//            // TODO 8: 格式化打印大模型想调用的具体工具名称和参数
//            String toolCallInfo = toolCallList.stream()
//                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
//                    .collect(Collectors.joining("\n"));
//            log.info(toolCallInfo);
//            // TODO 9: 核心分支判断：大模型到底要不要用工具？
//            // 提示：如果 toolCallList.isEmpty() 为 true，说明大模型直接回答了文本，不需要工具。
//            // 此时必须手动把 assistantMessage 加入 getMessageList()，然后 return false（告诉引擎无需进入 act 阶段）。
//            // 提示：否则（else），说明要用工具。此时千万不要加记忆，直接 return true（进入 act 阶段）。
//            // 如果不需要调用工具，返回 false
//            if (toolCallList.isEmpty()) {
//                // 只有不调用工具时，才需要手动记录助手消息
//                getMessageList().add(assistantMessage);
//                return false;
//            } else {
//                // 需要调用工具时，无需记录助手消息，因为调用工具时会自动记录
//                return true;
//            }
//        } catch (Exception e) {
//            log.error(getName() + "的思考过程遇到了问题：" + e.getMessage());
//            getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
//            return false;
//        }
//    }

@Override
public boolean think() {
    // [修复 3]: 防止提示词重复污染全局记忆
    // 简单防御：判断最后一条消息是不是已经加过这个提示词了，没有才加
    if (StrUtil.isNotBlank(getNextStepPrompt())) {
        Message lastMessage = CollUtil.getLast(getMessageList());
        if (!(lastMessage instanceof UserMessage && getNextStepPrompt().equals(lastMessage.getText()))) {
            getMessageList().add(new UserMessage(getNextStepPrompt()));
        }
    }
    try {
        // [修复 1]: 统一使用流式构建器，确保 chatOptions 的控制权绝对不被覆写
        ChatResponse chatResponse = getChatClient()
                .prompt() // 留空，不要传对象进去混合
                //历史对话消息
                .messages(getMessageList())
                .system(getSystemPrompt())
                .options(this.chatOptions) // 安全注入我们禁用了自动执行的 Option
                .toolCallbacks(availableTools)     // 挂载可用工具
                .call()
                .chatResponse();
        // 将大模型的回复存入全局变量，留给稍后的 act() 方法使用
        this.toolCallChatResponse = chatResponse;
        // 从返回体中剥离出助手消息
        AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
        // [修复 2]: 使用自带的 hasToolCalls() 规避空指针异常
        boolean hasTools = assistantMessage.hasToolCalls();
        List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
        int toolCount = hasTools ? toolCallList.size() : 0;
        String result = assistantMessage.getText();
        log.info(getName() + "的思考：" + result);
        log.info(getName() + "选择了 " + toolCount + " 个工具来使用");
        // 核心分支判断
        if (hasTools) {
            // 格式化打印大模型想调用的具体工具名称和参数
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            // 需要调用工具时，无需记录助手消息，返回 true 进入 act 阶段
            return true;
        } else {
            // 如果没有工具被调用，说明思考完成给出文本，记录助手消息，返回 false
            getMessageList().add(assistantMessage);
            return false;
        }
    } catch (Exception e) {
        // 打印完整的异常堆栈，方便排错
        log.error(getName() + "的思考过程遇到了问题：" + e.getMessage(), e);
        getMessageList().add(new AssistantMessage("处理时遇到了错误：" + e.getMessage()));
        return false;
    }
}
    @Override
    public String act() {
        // TODO 10: 兜底校验
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具需要调用";
        }
        Prompt prompt = new Prompt(getMessageList(), this.chatOptions);
        // TODO 11: 把大模型的指令交给管家，管家底层通过反射去执行你的 Java 本地方法
        // 提示：调用 toolCallingManager.executeToolCalls(prompt, toolCallChatResponse); 返回 ToolExecutionResult
        ToolExecutionResult toolExecutionResult = toolCallingManager
                .executeToolCalls(prompt,toolCallChatResponse); // 替换此处代码
        // TODO 12: 用执行完毕后包含最新工具结果的完整上下文，直接覆盖掉旧的记忆列表
        // 提示：调用 setMessageList(toolExecutionResult.conversationHistory());
        setMessageList(toolExecutionResult.conversationHistory());
        // TODO 13: 取出对话历史中的最后一条（即工具刚执行完的返回结果）
        // 提示：使用 (ToolResponseMessage) CollUtil.getLast(...) 转换并获取
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil
                .getLast(toolExecutionResult.conversationHistory()); // 替换此处代码
        // TODO 14: 终极停机检测
        // 提示：遍历 toolResponseMessage.getResponses()，使用 anyMatch 判断有没有哪个工具的名字叫 "doTerminate"。
        // 提示：如果有（terminateToolCalled 为 true），调用 setState(AgentState.FINISHED) 强制终止 Agent 的 for 循环。
        //ToolResponseMessage，本质上就是一份“工具执行完毕后的结算报告”（以及它们的执行结果）。
        //这里的每一个 response，都代表一个刚刚已经被成功运行过的工具。
        //response的内部不仅有工具的名字（response.name()），还有工具真正运行完产生的返回值（response.responseData()）。
        boolean terminateToolCalled = toolResponseMessage
                .getResponses()
                .stream()
                .anyMatch(reponse -> reponse.name()
                        .equals("doTerminate"));
        if(terminateToolCalled){
            setState(AgentState.FINISHED);
        }
        // TODO 15: 将工具执行的实际结果（如网页内容、成功写入路径等）拼接成字符串返回
        // 提示：遍历 toolResponseMessage.getResponses()，将其拼成 "工具 xxx 返回的结果：yyy" 的格式并用换行符连接。
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> "工具 " + response.name() + " 返回的结果：" + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info(results);
        return results; // 替换此处代码
    }
}
