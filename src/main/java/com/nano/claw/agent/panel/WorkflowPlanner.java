package com.nano.claw.agent.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流规划器（DAG 调度引擎）
 * <p>
 * 基于 WorkflowNode 的依赖关系构建有向无环图（DAG），
 * 按拓扑排序分组输出可并行执行的节点批次。
 * <p>
 * 执行策略：
 * - 同一批次内的节点可并行执行（无依赖关系）
 * - 下一批次的节点依赖上一批次全部完成
 * - 当前版本采用串行逐批执行（后续可扩展为真并行）
 *
 * @author Jason
 * @description Expert Panel DAG 工作流调度引擎
 * @date 2026/5/20
 */
public class WorkflowPlanner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPlanner.class);

    /** 工作流节点列表（按注册顺序） */
    private final List<WorkflowNode> nodes;

    /** nodeId -> WorkflowNode 索引 */
    private final Map<String, WorkflowNode> nodeMap;

    public WorkflowPlanner(List<WorkflowNode> nodes) {
        this.nodes = new ArrayList<>(nodes);
        this.nodeMap = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.getNodeId(), node);
        }
    }

    /**
     * 按拓扑排序生成执行批次
     * <p>
     * 每个批次中的节点可以并行执行。
     *
     * @return 执行批次列表，每批次是一组可并行的节点
     * @throws IllegalStateException 如果工作流存在环依赖
     */
    public List<List<WorkflowNode>> buildExecutionBatches() {
        List<List<WorkflowNode>> batches = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        Set<String> remaining = new HashSet<>();

        for (WorkflowNode node : nodes) {
            remaining.add(node.getNodeId());
        }

        int maxIterations = nodes.size() + 1;
        int iteration = 0;

        while (!remaining.isEmpty()) {
            if (++iteration > maxIterations) {
                throw new IllegalStateException("工作流存在循环依赖！剩余节点: " + remaining);
            }

            // 找出当前所有依赖已满足的节点（就绪节点）
            List<WorkflowNode> readyBatch = new ArrayList<>();
            for (String nodeId : remaining) {
                WorkflowNode node = nodeMap.get(nodeId);
                if (node.isReady(completed)) {
                    readyBatch.add(node);
                }
            }

            if (readyBatch.isEmpty() && !remaining.isEmpty()) {
                throw new IllegalStateException("工作流存在循环依赖！无法找到可执行节点，剩余: " + remaining);
            }

            // 将就绪节点加入批次
            batches.add(readyBatch);
            for (WorkflowNode node : readyBatch) {
                remaining.remove(node.getNodeId());
                completed.add(node.getNodeId());
            }

            log.debug("[WORKFLOW] 第 {} 批次节点: {}", batches.size(),
                    readyBatch.stream().map(WorkflowNode::getNodeId)
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b));
        }

        log.info("[WORKFLOW] 工作流共 {} 个节点，分为 {} 个执行批次", nodes.size(), batches.size());
        return batches;
    }

    /**
     * 获取所有节点
     *
     * @return 工作流节点列表
     */
    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    /**
     * 根据 nodeId 获取节点
     *
     * @param nodeId 节点ID
     * @return 节点，不存在返回 null
     */
    public WorkflowNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    /**
     * 校验工作流配置合法性（无孤立依赖，无环）
     *
     * @return 校验错误信息列表，空表示合法
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // 检查依赖节点是否存在
        for (WorkflowNode node : nodes) {
            for (String dep : node.getDependencies()) {
                if (!nodeMap.containsKey(dep)) {
                    errors.add("节点 [" + node.getNodeId() + "] 依赖不存在的节点: " + dep);
                }
            }
        }

        // 检查是否有环（尝试拓扑排序）
        if (errors.isEmpty()) {
            try {
                buildExecutionBatches();
            } catch (IllegalStateException e) {
                errors.add(e.getMessage());
            }
        }

        return errors;
    }
}
