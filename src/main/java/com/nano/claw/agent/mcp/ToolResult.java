package com.nano.claw.agent.mcp;

/**
 * 工具执行结果
 *
 * @author Jason
 * @description 工具调用的返回结果
 * @date 2026/5/19
 */
public class ToolResult {

    /** 是否执行成功 */
    private boolean success;
    /** 工具输出内容（成功时） */
    private String output;
    /** 错误信息（失败时） */
    private String error;

    public ToolResult() {
    }

    public ToolResult(boolean success, String output, String error) {
        this.success = success;
        this.output = output;
        this.error = error;
    }

    /**
     * 创建成功结果
     */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    /**
     * 创建失败结果
     */
    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * 将结果格式化为 Observation 文本，供 ReAct 循环使用
     */
    public String toObservation() {
        if (success) {
            return output;
        }
        return "工具调用失败: " + error;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}