package com.nano.claw.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内置工具 - 计算器工具
 * <p>
 * 执行数学表达式计算，支持 +, -, *, /, () 和负数
 *
 * @author Jason
 * @description 数学计算工具
 * @date 2026/5/19
 */
public class CalculatorTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "执行数学计算。输入JSON格式: {\"expression\": \"数学表达式，如 2+3*4 或 (10-2)*3\"}";
    }

    @Override
    public ToolResult execute(String input) {
        try {
            String expression = extractExpression(input);
            if (expression == null || expression.isEmpty()) {
                return ToolResult.failure("缺少必要参数: expression");
            }

            double result = evaluateExpression(expression);
            // 散数结果直接输出整数
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return ToolResult.success(String.valueOf((long) result));
            }
            return ToolResult.success(String.valueOf(result));

        } catch (Exception e) {
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    private String extractExpression(String input) {
        if (input == null) return null;
        try {
            JsonNode node = MAPPER.readTree(input);
            JsonNode exprNode = node.get("expression");
            if (exprNode == null || exprNode.isNull()) return null;
            return exprNode.asText().trim();
        } catch (Exception e) {
            return input.trim();
        }
    }

    /**
     * 数学表达式求值，支持 +, -, *, /, () 和负数
     */
    private double evaluateExpression(String expr) {
        final String newExpr = expr.replaceAll("\\s+", "");
        return new ExpressionParser(newExpr).parse();
    }

    private static class ExpressionParser {
        private final String expr;
        private int pos = 0;

        ExpressionParser(String expr) {
            this.expr = expr;
        }

        double parse() {
            double result = parseAddSub();
            if (pos < expr.length()) {
                throw new RuntimeException("意外的字符: " + expr.charAt(pos));
            }
            return result;
        }

        private double parseAddSub() {
            double result = parseMulDiv();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '+') {
                    pos++;
                    result += parseMulDiv();
                } else if (op == '-') {
                    pos++;
                    result -= parseMulDiv();
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseMulDiv() {
            double result = parseFactor();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '*') {
                    pos++;
                    result *= parseFactor();
                } else if (op == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) throw new RuntimeException("除数不能为零");
                    result /= divisor;
                } else {
                    break;
                }
            }
            return result;
        }

        private double parseFactor() {
            if (pos >= expr.length()) {
                throw new RuntimeException("表达式不完整");
            }

            char c = expr.charAt(pos);

            // 处理负号（一元运算符）
            if (c == '-') {
                pos++;
                return -parseFactor();
            }

            // 处理正号
            if (c == '+') {
                pos++;
                return parseFactor();
            }

            // 处理括号
            if (c == '(') {
                pos++; // skip '('
                double result = parseAddSub();
                if (pos >= expr.length() || expr.charAt(pos) != ')') {
                    throw new RuntimeException("缺少右括号");
                }
                pos++; // skip ')'
                return result;
            }

            // 处理数字
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) {
                    pos++;
                }
                return Double.parseDouble(expr.substring(start, pos));
            }

            throw new RuntimeException("意外的字符: " + c);
        }
    }
}