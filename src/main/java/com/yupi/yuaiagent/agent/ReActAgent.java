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
                // 纯思考步骤：没有工具执行，显式标记，供 SSE process 事件的 tool 字段使用
                recordStepTools("think");
                return "思考完成，无需使用工具";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "步骤执行失败：" + e.getMessage();
        }
    }
}
