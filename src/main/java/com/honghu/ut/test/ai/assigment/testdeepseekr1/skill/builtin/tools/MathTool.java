package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.tools;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeSkill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeTool;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.ToolParam;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.DangerLevel;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置数学计算技能。
 *
 * <p>这里没有使用 JavaScript、SpEL、脚本引擎或任何通用 eval。表达式计算器只支持数字和运算符，
 * 因此模型即使传入恶意字符串，也只能被当成“非法数学表达式”，不会执行代码、访问文件或发起网络请求。</p>
 */
@NativeSkill(
        key = "math",
        displayName = "计算器",
        description = "执行安全的数学计算和基础统计",
        icon = "MATH",
        category = "通用",
        defaultEnabled = true,
        mandatory = false,
        requiredRole = User.UserRole.USER)
public class MathTool {

    /**
     * 计算一个安全的数学表达式。
     *
     * @param expression 数学表达式，例如 {@code (3 + 4) * 2}
     * @return 原表达式、数值结果和适合展示的文本结果
     */
    @NativeTool(
            name = "calculate",
            description = "安全计算数学表达式。支持 +、-、*、/、%、^、括号和小数，不执行任何代码。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> calculate(
            @ToolParam(description = "数学表达式，例如 (3 + 4) * 2 或 2^8。") String expression) {
        if (expression == null || expression.isBlank()) {
            // 表达式为空时无法计算，直接抛参数错误给工具调用链。
            throw new IllegalArgumentException("expression must not be blank");
        }
        // 使用下方自定义递归下降解析器，只解析数学语法，不执行任何代码。
        double value = new ExpressionParser(expression).parse();
        // 返回结构化结果：模型可以引用 result 做后续计算，也可以直接展示 resultText。
        return Map.of(
                "expression", expression,
                "result", value,
                "resultText", trimNumber(value)
        );
    }

    /**
     * 计算一组数字的描述性统计量。
     *
     * @param values 模型提供的数字数组
     * @return count、sum、average、min、max、median、variance、standardDeviation
     */
    @NativeTool(
            name = "statistics",
            description = "对数字数组计算 count、sum、average、min、max、median、variance、standardDeviation。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> statistics(
            @ToolParam(description = "数字数组，例如 [1,2,3,4]。", itemType = Double.class) List<Double> values) {
        if (values == null || values.isEmpty()) {
            // 没有数字时统计没有意义。
            throw new IllegalArgumentException("values must not be empty");
        }
        // 清理 null、NaN、Infinity，并排序；排序后 min/max/median 可以直接取位置。
        List<Double> cleaned = values.stream()
                .filter(value -> value != null && Double.isFinite(value))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (cleaned.isEmpty()) {
            // 如果清理后没有有限数字，说明输入全是非法数值。
            throw new IllegalArgumentException("values must contain at least one finite number");
        }

        // 样本数量。
        int count = cleaned.size();
        // 求和。
        double sum = cleaned.stream().mapToDouble(Double::doubleValue).sum();
        // 平均值。
        double average = sum / count;
        // 排序后第一个就是最小值。
        double min = cleaned.get(0);
        // 排序后最后一个就是最大值。
        double max = cleaned.get(count - 1);
        // 奇数个取中间值，偶数个取中间两个值平均数。
        double median = count % 2 == 1
                ? cleaned.get(count / 2)
                : (cleaned.get(count / 2 - 1) + cleaned.get(count / 2)) / 2.0;
        // 方差：每个值到平均值的平方差求平均。
        double variance = cleaned.stream()
                .mapToDouble(value -> Math.pow(value - average, 2))
                .sum() / count;

        // 使用 LinkedHashMap 保持返回字段顺序稳定，便于前端或模型阅读。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("sum", sum);
        result.put("average", average);
        result.put("min", min);
        result.put("max", max);
        result.put("median", median);
        result.put("variance", variance);
        // 标准差是方差平方根。
        result.put("standardDeviation", Math.sqrt(variance));
        return result;
    }

    /**
     * 去掉整数结果末尾的 .0，让展示文本更自然。
     */
    private static String trimNumber(double value) {
        if (value == Math.rint(value)) {
            // Math.rint 返回最接近整数的 double；相等说明 value 本质是整数。
            return String.valueOf((long) value);
        }
        // 非整数保留 Double 默认字符串表示。
        return Double.toString(value);
    }

    /**
     * 极简递归下降数学表达式解析器。
     *
     * <p>语法规则：expression -> term ((+|-) term)*；term -> power ((*|/|%) power)*；
     * power -> unary (^ power)?；unary -> (+|-) unary | primary；
     * primary -> number | '(' expression ')'。指数运算是右结合，所以 {@code 2^3^2}
     * 会按 {@code 2^(3^2)} 计算。</p>
     */
    private static final class ExpressionParser {
        /** 原始表达式文本。 */
        private final String text;

        /** 当前解析到的字符下标。 */
        private int pos;

        /**
         * 创建一个只服务于单次表达式解析的解析器实例。
         *
         * <p>解析器内部有可变的 {@link #pos} 游标，因此一个实例只解析一个表达式。
         * 每次调用 {@link MathTool#calculate(String)} 都会 new 一个新解析器，避免并发调用之间共享状态。</p>
         *
         * @param text 原始数学表达式文本，后续通过 pos 从左到右逐字符消费
         */
        private ExpressionParser(String text) {
            // 保存原始文本；解析过程中只移动 pos，不修改字符串。
            this.text = text;
        }

        /**
         * 解析完整表达式入口。
         */
        private double parse() {
            // 从最低优先级的 expression 开始解析。
            double value = parseExpression();
            // 表达式末尾允许有空白。
            skipWhitespace();
            if (pos != text.length()) {
                // 如果还有未消费字符，说明出现了不认识的 token。
                throw new IllegalArgumentException("Unexpected token at position " + pos);
            }
            if (!Double.isFinite(value)) {
                // 防止除法、指数等算出 Infinity 或 NaN。
                throw new IllegalArgumentException("Expression result is not finite");
            }
            return value;
        }

        /**
         * 解析加减法层级。
         */
        private double parseExpression() {
            // expression 的左侧先解析一个 term。
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (match('+')) {
                    // 遇到 + 就把后一个 term 加到当前值。
                    value += parseTerm();
                } else if (match('-')) {
                    // 遇到 - 就把后一个 term 从当前值中减掉。
                    value -= parseTerm();
                } else {
                    // 没有 + 或 -，当前 expression 结束。
                    return value;
                }
            }
        }

        /**
         * 解析乘、除、取模层级。
         */
        private double parseTerm() {
            // term 的左侧先解析一个 power。
            double value = parsePower();
            while (true) {
                skipWhitespace();
                if (match('*')) {
                    // 乘法优先级高于加减。
                    value *= parsePower();
                } else if (match('/')) {
                    // 除法右侧也按 power 解析。
                    double divisor = parsePower();
                    if (divisor == 0.0d) throw new IllegalArgumentException("Division by zero");
                    value /= divisor;
                } else if (match('%')) {
                    // 取模同样禁止除数为 0。
                    double divisor = parsePower();
                    if (divisor == 0.0d) throw new IllegalArgumentException("Modulo by zero");
                    value %= divisor;
                } else {
                    // 没有乘除取模，当前 term 结束。
                    return value;
                }
            }
        }

        /**
         * 解析指数运算层级。
         */
        private double parsePower() {
            // 指数的底数先解析成 unary。
            double base = parseUnary();
            skipWhitespace();
            if (match('^')) {
                // 递归调用 parsePower 使指数运算右结合。
                return Math.pow(base, parsePower());
            }
            return base;
        }

        /**
         * 解析一元正负号。
         */
        private double parseUnary() {
            skipWhitespace();
            // 连续的一元 + 直接跳过，例如 +++1。
            if (match('+')) return parseUnary();
            // 一元 - 对后面的 unary 取反，例如 --1 会变成 1。
            if (match('-')) return -parseUnary();
            return parsePrimary();
        }

        /**
         * 解析数字或括号表达式。
         */
        private double parsePrimary() {
            skipWhitespace();
            if (match('(')) {
                // 左括号后递归解析一个完整 expression。
                double value = parseExpression();
                skipWhitespace();
                if (!match(')')) {
                    // 括号必须闭合，否则表达式结构不完整。
                    throw new IllegalArgumentException("Missing ')' at position " + pos);
                }
                return value;
            }
            // 不是括号时必须是数字。
            return parseNumber();
        }

        /**
         * 解析十进制数字。
         */
        private double parseNumber() {
            skipWhitespace();
            // 记录数字开始位置，最后 substring 交给 Double.parseDouble。
            int start = pos;
            // 同一个数字里只能出现一个小数点。
            boolean dotSeen = false;
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isDigit(ch)) {
                    // 数字字符属于当前 number。
                    pos++;
                    continue;
                }
                if (ch == '.' && !dotSeen) {
                    // 第一次遇到小数点时允许继续读取。
                    dotSeen = true;
                    pos++;
                    continue;
                }
                // 遇到非数字且不是合法小数点，number 结束。
                break;
            }
            if (start == pos) {
                // 一个字符都没有读到，说明当前位置不是数字。
                throw new IllegalArgumentException("Expected number at position " + pos);
            }
            return Double.parseDouble(text.substring(start, pos));
        }

        /**
         * 如果当前位置字符等于 expected，就消费它。
         */
        private boolean match(char expected) {
            if (pos < text.length() && text.charAt(pos) == expected) {
                // 匹配成功后位置前进一步。
                pos++;
                return true;
            }
            return false;
        }

        /**
         * 跳过表达式中的空白字符。
         */
        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                // 空格、换行、制表符都不参与数学语法。
                pos++;
            }
        }
    }
}
