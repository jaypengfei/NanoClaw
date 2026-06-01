package com.nano.claw.agent.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 专家团最终输出聚合对象
 * <p>
 * 汇聚所有专家角色的产出物，生成综合交付报告。
 *
 * @author Jason
 * @description Expert Panel 最终输出
 * @date 2026/5/20
 */
public class PanelResult {

    /** 是否全部成功 */
    private boolean success;

    /** 最终聚合报告（Markdown 格式） */
    private String finalReport;

    /** 各角色产出物快照，key=role，value=产出内容 */
    private Map<String, String> artifacts;

    /** 执行的工作流节点列表（含状态和耗时） */
    private List<WorkflowNode> workflowNodes = new ArrayList<>();

    /** 总耗时（毫秒） */
    private long totalDurationMs;

    /** 累计消耗 token */
    private int totalTokens;

    /** 错误信息（失败时） */
    private String error;

    /** 执行轮次（专家数量） */
    private int expertCount;

    public PanelResult() {
    }

    public static PanelResult success(String finalReport, Map<String, String> artifacts,
                                       List<WorkflowNode> nodes, long durationMs, int tokens) {
        PanelResult result = new PanelResult();
        result.setSuccess(true);
        result.setFinalReport(finalReport);
        result.setArtifacts(artifacts);
        result.setWorkflowNodes(nodes);
        result.setTotalDurationMs(durationMs);
        result.setTotalTokens(tokens);
        result.setExpertCount(artifacts != null ? artifacts.size() : 0);
        return result;
    }

    public static PanelResult failure(String error) {
        PanelResult result = new PanelResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    // ==================== Getters & Setters ====================

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getFinalReport() { return finalReport; }
    public void setFinalReport(String finalReport) { this.finalReport = finalReport; }

    public Map<String, String> getArtifacts() { return artifacts; }
    public void setArtifacts(Map<String, String> artifacts) { this.artifacts = artifacts; }

    public List<WorkflowNode> getWorkflowNodes() { return workflowNodes; }
    public void setWorkflowNodes(List<WorkflowNode> workflowNodes) { this.workflowNodes = workflowNodes; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getExpertCount() { return expertCount; }
    public void setExpertCount(int expertCount) { this.expertCount = expertCount; }
}
