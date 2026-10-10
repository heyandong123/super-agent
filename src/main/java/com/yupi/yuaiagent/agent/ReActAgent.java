//package com.yupi.yuaiagent.agent;
//
//import lombok.Data;
//import lombok.EqualsAndHashCode;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * ReAct (Reasoning and Acting) 模式的代理抽象类
// * 实现了思考-行动的循环模式
// */
//@EqualsAndHashCode(callSuper = true)
//@Data
//@Slf4j
//public abstract class ReActAgent extends BaseAgent {
//
//    /**
//     * 处理当前状态并决定下一步行动
//     *
//     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
//     */
//    public abstract boolean think();
//
//    /**
//     * 执行决定的行动
//     *
//     * @return 行动执行结果
//     */
//    public abstract String act();
//
//    /**
//     * 执行单个步骤：思考和行动
//     *
//     * @return 步骤执行结果
//     */
//    @Override
//    public String step() {
//        try {
//            // 先思考
//            boolean shouldAct = think();
//            if (!shouldAct) {
//                return "思考完成 - 无需行动";
//            }
//            // 再行动
//            return act();
//        } catch (Exception e) {
//            // 记录异常日志
//            e.printStackTrace();
//            return "步骤执行失败：" + e.getMessage();
//        }
//    }
//
//}
package com.yupi.yuaiagent.agent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 核心职责：将父类粗粒度的 step() 拆解为学术界著名的“思考(Think) -> 行动(Act)”标准范式。
 */
// [核心原理 1]: 当你使用 Lombok 的 @Data 且继承了父类时，必须加这行注解。
// 它告诉框架在比较两个 Agent 对象是否相同时，必须把父类（BaseAgent）里的 state、messageList 等属性也算进去。
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {
//    /**
//     * 阶段一：大脑思考 (Reasoning)
//     * [原理]: 每次执行前，先让大模型分析当前局势（比如：“用户想查天气，我需要调用搜索工具”）。
//     * [提示]: 声明一个抽象方法 abstract boolean think()。返回 true 代表想好策略准备动手，false 代表认为任务已完成，无需继续折腾。
//     */
//    public abstract boolean think();
//    /**
//     * 阶段二：物理行动 (Acting)
//     * [原理]: 真正去调用你之前写的联网搜索、执行终端命令等 @Tool 工具。
//     * [提示]: 声明一个抽象方法 abstract String act()。返回行动的文字结果（比如“搜索到明天北京下雨”）。
//     */
//    public abstract String act();
//    /**
//     * 重写父类 BaseAgent 的心跳执行引擎
//     * [原理]: 典型的“模板方法模式”。父类 BaseAgent 里的 for 循环每次心跳都会调这个方法。
//     */
//    @Override
//    public String step() {
//        try {
//            // TODO 1: 强制先思考纪律
//            // 提示: 调用 this.think()，用 boolean shouldAct 接收。
//            boolean shouldAct = this.think(); // 替换此行
//            // TODO 2: 提前退出机制
//            // 提示: 如果 !shouldAct，说明大模型认为没必要动手了，直接 return "思考完成 - 无需行动"。
//            // 你的代码写在这里...
//            if(!shouldAct) return "Thought done - No action required";
//            // TODO 3: 思考通过，进入行动
//            // 提示: 直接 return act() 的执行结果。
//            return act(); // 替换此行
//        } catch (Exception e) {
//            // TODO 4: 单步防崩溃保护
//            // 核心考量: ReAct 是多步流转的，某一步工具挂了（比如网络抖动），不代表整个 Agent 死亡，记录异常后返回给大模型让它自己纠错。
//            // 提示: e.printStackTrace()，并 return "步骤执行失败：" + e.getMessage()。
//            e.printStackTrace();
//            return "步骤执行失败：" + e.getMessage(); // 替换此行
//        }
//    }
    public abstract boolean think();
    public abstract String act();
    @Override
    public String step(){
        try {
            Boolean thought = this.think();
            if(thought){
                return act();
            }
            else{
                return "思考完成，无需使用工具";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "步骤执行失败：" + e.getMessage();
        }
    }
}
