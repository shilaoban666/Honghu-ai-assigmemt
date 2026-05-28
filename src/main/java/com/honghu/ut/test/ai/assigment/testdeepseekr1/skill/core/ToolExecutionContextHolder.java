package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

/**
 * 工具调用上下文的线程级临时存储。
 * <p>
 * 内置工具方法本身只暴露给模型需要填写的业务参数；用户 id、会话 id 这类安全上下文由后端放入 ThreadLocal。
 * 使用后必须 clear，避免 Web 容器复用线程时把上一次请求的身份泄漏到下一次工具调用。
 */
public final class ToolExecutionContextHolder {
    // 每个请求线程保存自己的 ToolExecutionContext，不在线程之间共享。
    private static final ThreadLocal<ToolExecutionContext> HOLDER = new ThreadLocal<>();

    private ToolExecutionContextHolder() {
    }

    public static void set(ToolExecutionContext context) {
        // 工具执行前写入上下文，工具内部可通过 get() 获取当前用户和会话。
        HOLDER.set(context);
    }

    public static ToolExecutionContext get() {
        // 没有上下文时返回 null，工具需要自行决定是否允许匿名执行。
        return HOLDER.get();
    }

    public static void clear() {
        // 执行结束必须移除，而不是 set(null)，这样 ThreadLocalMap 可以释放 value 引用。
        HOLDER.remove();
    }
}
