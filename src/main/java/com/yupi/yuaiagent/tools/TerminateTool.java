package com.yupi.yuaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 终止工具（作用是让自主规划智能体能够合理地中断）
 * <p>
 * taskChecklist 参数强制模型在终止前逐项核对用户任务的所有要求——
 * Agent 层会拿这份清单与原始任务做校验，有遗漏则拒绝终止并反馈模型继续完成。
 */
public class TerminateTool {

    @Tool(description = """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            Call this tool only when you have finished ALL requirements of the user's task.
            """)
    public String doTerminate(
            @ToolParam(description = "逐项列出用户任务的所有要求及完成状态，例如：1. 搜索天气-已完成 2. 保存到文件-已完成") String taskChecklist) {
        return taskChecklist;
    }
}
