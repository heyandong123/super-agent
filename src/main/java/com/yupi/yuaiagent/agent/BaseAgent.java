//package com.yupi.yuaiagent.agent;
//
//import cn.hutool.core.util.StrUtil;
//import com.yupi.yuaiagent.agent.model.AgentState;
//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.messages.UserMessage;
//import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
//
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.CompletableFuture;
//
///**
// * 抽象基础代理类（Agent 的超级心脏）
// * 核心职责：维护状态机、管理短期记忆（Message Context）、驱动 ReAct (思考-行动) 循环。
// */
//@Data
//@Slf4j
//public abstract class BaseAgent {
//
//    // --- 核心属性已就绪 ---
//    private String name;
//    private String systemPrompt;
//    private String nextStepPrompt;
//    private AgentState state = AgentState.IDLE;
//    private int currentStep = 0;
//    private int maxSteps = 10;
//    private ChatClient chatClient;
//    private List<Message> messageList = new ArrayList<>();
//
//    /**
//     * 同步运行代理（会阻塞当前线程，直到大模型把所有步骤思考执行完毕）
//     */
//    public String run(String userPrompt) {
//        // [原理]: 并发保护。Agent 在执行复杂任务时绝对不能被其他线程重入，否则会话记忆（MessageList）会被严重污染。
//        // [提示]: 写一个 if 判断，如果 this.state != AgentState.IDLE，直接抛出 RuntimeException。
//        // 你的代码写在这里...
//        if (this.state != AgentState.IDLE) {
//            throw new RuntimeException("The code status is incorrect. The status should be IDLE!");
//        }
//        // [原理]: 防御性编程。拦截空字符串，防止浪费大模型的 Token 算力。
//        // [提示]: 写一个 if 判断，如果 StrUtil.isBlank(userPrompt) 为 true，抛出 RuntimeException。
//        // 你的代码写在这里...
//        if (StrUtil.isBlank(userPrompt)) {
//            throw new RuntimeException("The user prompt word is empty!");
//        }
//        // [原理]: 状态机扭转。加锁成功，正式进入执行态。
//        // [提示]: 将 this.state 赋值为 AgentState.RUNNING。
//        // 你的代码写在这里...
//        this.state = AgentState.RUNNING;
//        // [原理]: 短期记忆写入。把用户本次的问题作为对话历史的起点（Context）。
//        // [提示]: 调用 messageList.add()，传入 new UserMessage(userPrompt)。
//        // 你的代码写在这里...
//        messageList.add(new UserMessage(userPrompt));
//        List<String> results = new ArrayList<>();
//
//        try {
//            // [原理]: ReAct (Reason+Act) 核心引擎。强制设定 maxSteps 是为了防止大模型陷入“无限报错-重试”的死循环，导致 API 计费破产。
//            // [提示]: 写一个 for 循环，初始化 i=0；条件为 (i < maxSteps && state != AgentState.FINISHED)；i++。
//            // 你的代码写在这里 (包含以下逻辑)...
//            for (int i = 0; i < maxSteps && this.state != AgentState.FINISHED; i++) {
//                // [原理]: 游标同步，方便底层日志追踪和限流。
//                // [提示]: 计算 stepNumber = i + 1；将其赋值给 currentStep；打印 info 级别日志。
//                int stepNumber = i + 1;
//                currentStep = stepNumber;
//                // [原理]: 触发思考动作（多态设计）。调用子类实现的逻辑，父类只管调度。
//                // [提示]: 调用 this.step() 获取 String 返回值 stepResult。
//                String stepResult = this.step();
//                // [原理]: 格式化单步结果并归档。
//                // [提示]: 将 "Step " + stepNumber + ": " + stepResult 拼装，调用 results.add() 塞入列表。
//                results.add("Step" + stepNumber + ": " + stepResult);
//                // [原理]: 循环退出兜底检查。如果是因为撞到了 maxSteps 墙被强行弹出的，必须标记状态。
//                // [提示]: 判断 currentStep >= maxSteps，如果是，将 state 设为 FINISHED，并向 results 插入一条 Terminated 超限提示语。
//                // 你的代码写在这里...
//                if (currentStep >= maxSteps) {
//                    state = AgentState.FINISHED;
//                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
//                }
//            }
//            // [原理]: 聚合最终答卷。
//            // [提示]: 用 String.join("\n", results) 将列表合并成大字符串并 return。
//            return String.join("\n", results);
//            } catch(Exception e){
//                // [原理]: 异常状态流转。捕获网络波动或工具调用异常，防止当前实例永久卡死在 RUNNING 态。
//                // [提示]: state 设为 ERROR；log.error 打印堆栈；return 带有 e.getMessage() 的错误信息。
//                this.state = AgentState.ERROR;
//                log.error("error executing agent", e);
//                return "执行错误" + e.getMessage();
//            } finally{
//                // [原理]: 资源释放兜底。无论任务成功还是崩溃，都必须交出底层资源（如刚才生成的临时 PDF 或网络连接）。
//                // [提示]: 调用 this.cleanup()。
//                // 你的代码写在这里...
//                this.cleanup();
//            }
//    }
//    /**
//     //     * 运行代理（流式输出）
//     //     *
//     //     * @param userPrompt 用户提示词
//     //     * @return 执行结果
//     //     */
//    public SseEmitter runStream(String userPrompt) {
//        // 创建一个超时时间较长的 SseEmitter
//        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
//        // 使用线程异步处理，避免阻塞主线程
//        CompletableFuture.runAsync(() -> {
//            // 1、基础校验
//            try {
//                if (this.state != AgentState.IDLE) {
//                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
//                    sseEmitter.complete();
//                    return;
//                }
//                if (StrUtil.isBlank(userPrompt)) {
//                    sseEmitter.send("错误：不能使用空提示词运行代理");
//                    sseEmitter.complete();
//                    return;
//                }
//            } catch (Exception e) {
//                sseEmitter.completeWithError(e);
//            }
//            // 2、执行，更改状态
//            this.state = AgentState.RUNNING;
//            // 记录消息上下文
//            messageList.add(new UserMessage(userPrompt));
//            // 保存结果列表
//            List<String> results = new ArrayList<>();
//            try {
//                // 执行循环
//                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
//                    int stepNumber = i + 1;
//                    currentStep = stepNumber;
//                    log.info("Executing step {}/{}", stepNumber, maxSteps);
//                    // 单步执行
//                    String stepResult = step();
//                    String result = "Step " + stepNumber + ": " + stepResult;
//                    results.add(result);
//                    // 输出当前每一步的结果到 SSE
//                    sseEmitter.send(result);
//                }
//                // 检查是否超出步骤限制
//                if (currentStep >= maxSteps) {
//                    state = AgentState.FINISHED;
//                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
//                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
//                }
//                // 正常完成
//                sseEmitter.complete();
//            } catch (Exception e) {
//                state = AgentState.ERROR;
//                log.error("error executing agent", e);
//                try {
//                    sseEmitter.send("执行错误：" + e.getMessage());
//                    sseEmitter.complete();
//                } catch (IOException ex) {
//                    sseEmitter.completeWithError(ex);
//                }
//            } finally {
//                // 3、清理资源
//                this.cleanup();
//            }
//        });
//
//        // 设置超时回调
//        sseEmitter.onTimeout(() -> {
//            this.state = AgentState.ERROR;
//            this.cleanup();
//            log.warn("SSE connection timeout");
//        });
//        // 设置完成回调
//        sseEmitter.onCompletion(() -> {
//            //如果agent状态还是RUNNING，说明是用户主动断开或者正常结束的
//            if (this.state == AgentState.RUNNING) {
//                this.state = AgentState.FINISHED;
//            }
//            this.cleanup();
//            log.info("SSE connection completed");
//        });
//        return sseEmitter;
//    }
//
//    // --- 模板方法与钩子 ---
//    public abstract String step();
//
//    protected void cleanup() { }
//}
package com.yupi.yuaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类（Agent 的超级心脏）
 * 核心职责：维护状态机、管理短期记忆（Message Context）、驱动 ReAct (思考-行动) 循环。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // --- 核心属性已就绪 ---
//    private String name;
//    private String systemPrompt;
//    private String nextStepPrompt;
//    private AgentState state = AgentState.IDLE;
//    private int currentStep = 0;
//    private int maxSteps = 10;
//    private ChatClient chatClient;
//    private List<Message> messageList = new ArrayList<>();

    private String name;
    private List<Message> messageList = new ArrayList<>();
    private String systemPrompt;
    private String nextStepPrompt;
    private AgentState state = AgentState.IDLE;
    private int currentStep = 0;
    private int maxSteps = 10;
    private ChatClient chatClient;
    /**
     * 同步运行代理（会阻塞当前线程，直到大模型把所有步骤思考执行完毕）
     */
    public String run(String userPrompt) {
//        // [原理]: 并发保护。Agent 在执行复杂任务时绝对不能被其他线程重入，否则会话记忆（MessageList）会被严重污染。
//        // [提示]: 写一个 if 判断，如果 this.state != AgentState.IDLE，直接抛出 RuntimeException。
//        // 你的代码写在这里...
//        if (this.state != AgentState.IDLE) {
//            throw new RuntimeException("The code status is incorrect. The status should be IDLE!");
//        }
//        // [原理]: 防御性编程。拦截空字符串，防止浪费大模型的 Token 算力。
//        // [提示]: 写一个 if 判断，如果 StrUtil.isBlank(userPrompt) 为 true，抛出 RuntimeException。
//        // 你的代码写在这里...
//        if (StrUtil.isBlank(userPrompt)) {
//            throw new RuntimeException("The user prompt word is empty!");
//        }
//        // [原理]: 状态机扭转。加锁成功，正式进入执行态。
//        // [提示]: 将 this.state 赋值为 AgentState.RUNNING。
//        // 你的代码写在这里...
//        this.state = AgentState.RUNNING;
//        // [原理]: 短期记忆写入。把用户本次的问题作为对话历史的起点（Context）。
//        // [提示]: 调用 messageList.add()，传入 new UserMessage(userPrompt)。
//        // 你的代码写在这里...
//        messageList.add(new UserMessage(userPrompt));
//        List<String> results = new ArrayList<>();
//
//        try {
//            // [原理]: ReAct (Reason+Act) 核心引擎。强制设定 maxSteps 是为了防止大模型陷入“无限报错-重试”的死循环，导致 API 计费破产。
//            // [提示]: 写一个 for 循环，初始化 i=0；条件为 (i < maxSteps && state != AgentState.FINISHED)；i++。
//            // 你的代码写在这里 (包含以下逻辑)...
//            for (int i = 0; i < maxSteps && this.state != AgentState.FINISHED; i++) {
//                // [原理]: 游标同步，方便底层日志追踪和限流。
//                // [提示]: 计算 stepNumber = i + 1；将其赋值给 currentStep；打印 info 级别日志。
//                int stepNumber = i + 1;
//                currentStep = stepNumber;
//                // [原理]: 触发思考动作（多态设计）。调用子类实现的逻辑，父类只管调度。
//                // [提示]: 调用 this.step() 获取 String 返回值 stepResult。
//                String stepResult = this.step();
//                // [原理]: 格式化单步结果并归档。
//                // [提示]: 将 "Step " + stepNumber + ": " + stepResult 拼装，调用 results.add() 塞入列表。
//                results.add("Step" + stepNumber + ": " + stepResult);
//                // [原理]: 循环退出兜底检查。如果是因为撞到了 maxSteps 墙被强行弹出的，必须标记状态。
//                // [提示]: 判断 currentStep >= maxSteps，如果是，将 state 设为 FINISHED，并向 results 插入一条 Terminated 超限提示语。
//                // 你的代码写在这里...
//                if (currentStep >= maxSteps) {
//                    state = AgentState.FINISHED;
//                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
//                }
//            }
//            // [原理]: 聚合最终答卷。
//            // [提示]: 用 String.join("\n", results) 将列表合并成大字符串并 return。
//            return String.join("\n", results);
//        } catch(Exception e){
//            // [原理]: 异常状态流转。捕获网络波动或工具调用异常，防止当前实例永久卡死在 RUNNING 态。
//            // [提示]: state 设为 ERROR；log.error 打印堆栈；return 带有 e.getMessage() 的错误信息。
//            this.state = AgentState.ERROR;
//            log.error("error executing agent", e);
//            return "执行错误" + e.getMessage();
//        } finally{
//            // [原理]: 资源释放兜底。无论任务成功还是崩溃，都必须交出底层资源（如刚才生成的临时 PDF 或网络连接）。
//            // [提示]: 调用 this.cleanup()。
//            // 你的代码写在这里...
//            this.cleanup();
//        }
        if(state != AgentState.IDLE){
            throw new RuntimeException("agent状态错误！");
        }
        if(StrUtil.isBlank(userPrompt)){
            throw new RuntimeException("用户提示词为空！");
        }
        List<String> Results = new ArrayList<>();
        messageList.add(new UserMessage(userPrompt));
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                String stepRssult = step();
                Results.add("Step" + currentStep + ": " + stepRssult);
                if(currentStep >= maxSteps){
                    this.state = AgentState.FINISHED;
                    Results.add("agent到达最大步数");
                }
            }
            return String.join("\n",Results);
        } catch (Exception e) {
            this.state = AgentState.ERROR;
            return "出错了：" + e.getMessage();
        } finally {
            this.cleanup();
        }
    }
    /**
     //     * 运行代理（流式输出）
     //     *
     //     * @param userPrompt 用户提示词
     //     * @return 执行结果
     //     */
    public SseEmitter runStream(String userPrompt) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter sseEmitter = new SseEmitter(300000L); // 5 分钟超时
        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = step();
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    // 输出当前每一步的结果到 SSE
                    sseEmitter.send(result);
                }
                // 检查是否超出步骤限制
                if (currentStep >= maxSteps) {
                    state = AgentState.FINISHED;
                    results.add("Terminated: Reached max steps (" + maxSteps + ")");
                    sseEmitter.send("执行结束：达到最大步骤（" + maxSteps + "）");
                }
                // 正常完成
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                // 3、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        // 设置完成回调
        sseEmitter.onCompletion(() -> {
            //如果agent状态还是RUNNING，说明是用户主动断开或者正常结束的
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    // --- 模板方法与钩子 ---
    public abstract String step();

    protected void cleanup() { }
}
