package com.nano.claw.agent.mcp;

/**
 * 内置工具 - 计算器工具
 * <p>
 * 执行数学表达式计算
 *
 * @author Jason
 * @description 数学计算工具
 * @date 2026/5/19
 */
public class CalculatorTool implements Tool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "执行数学计算。输入JSON格式: {\"expression\": \"数学表达式，如 2+3*4\"}";
    }

    @Override
    public ToolResult execute(String input) {
        try {
            // 简单提取 expression 字段
            String expression = extractExpression(input);
            if (expression == null || expression.isEmpty()) {
                return ToolResult.failure("缺少必要参数: expression");
            }

            // 安全计算简单数学表达式（仅支持数字和基本运算符）
            double result = evaluateExpression(expression);
            return ToolResult.success(String.valueOf(result));

        } catch (Exception e) {
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    private String extractExpression(String input) {
        if (input == null) {
            return null;
        }
        // 尝试从JSON中提取
        String pattern = "\"expression\"";
        int idx = input.indexOf(pattern);
        if (idx >= 0) {
            int colonIdx = input.indexOf(":", idx + pattern.length());
            if (colonIdx >= 0) {
                int startQuote = input.indexOf("\"", colonIdx + 1);
                if (startQuote >= 0) {
                    int endQuote = input.indexOf("\"", startQuote + 1);
                    if (endQuote >= 0) {
                        return input.substring(startQuote + 1, endQuote);
                    }
                }
            }
        }
        // 如果不是JSON格式，直接当作表达式
        return input.trim();
    }

    /**
     * 简单的数学表达式求值
     * 仅支持 +, -, *, /, () 和数字
     */
    private double evaluateExpression(String expr) {
        // 移除空格
        final String newExpr = expr.replaceAll("\\s+", "");
        return new Object() {
            int pos = 0;

            double parse() {
                double result = parseTerm();
                while (pos < newExpr.length()) {
                    char op = newExpr.charAt(pos);
                    if (op == '+') { pos++; result += parseTerm(); }
                    else if (op == '-') {
                        pos++; result -= parseTerm();
                    } else {
                        break;
                    }
                }
                return result;
            }

            double parseTerm() {
                double result = parseFactor();
                while (pos < newExpr.length()) {
                    char op = newExpr.charAt(pos);
                    if (op == '*') { pos++; result *= parseFactor(); }
                    else if (op == '/') { pos++; result /= parseFactor(); }
                    else {
                        break;
                    }
                }
                return result;
            }

            double parseFactor() {
                if (pos < newExpr.length() && newExpr.charAt(pos) == '(') {
                    pos++; // skip '('
                    double result = parse();
                    pos++; // skip ')'
                    return result;
                }
                int start = pos;
                while (pos < newExpr.length() && (Character.isDigit(newExpr.charAt(pos)) || newExpr.charAt(pos) == '.')) {
                    pos++;
                }
                return Double.parseDouble(newExpr.substring(start, pos));
            }
        }.parse();
    }
}