package com.honghu.ai.assigment.skill.core;

/**
 * 把可信工具上下文暴露给内置工具的 ThreadLocal 桥接器。
 *
 * <p>标记了 {@code @NativeTool} 的 Java 方法应该只声明“模型需要填写的业务参数”。如果工具需要当前用户、
 * 当前会话或当前消息，就从这个 holder 读取，而不是声明一个可被模型伪造的 {@code userId} 参数。
 * 因为 Web 容器线程会复用，所以每次工具执行结束后必须清理 ThreadLocal。</p>
 */
public final class ToolExecutionContextHolder {

    /** 当前线程上的工具调用上下文；每次工具执行结束必须 remove。 */
    private static final ThreadLocal<ToolExecutionContext> HOLDER = new ThreadLocal<>();

    /**
     * 工具类不允许实例化；所有方法都是 static。
     */
    private ToolExecutionContextHolder() {
    }

    /**
     * 保存当前线程正在执行的工具调用上下文。
     */
    public static void set(ToolExecutionContext context) {
        // 将本次调用的可信上下文绑定到当前执行线程。
        HOLDER.set(context);
    }

    /**
     * 返回当前线程的工具调用上下文。
     *
     * <p>如果工具是在测试或管理任务中直接调用、没有经过正常模型 callback 链路，则可能返回 null。</p>
     */
    public static ToolExecutionContext get() {
        // 没有上下文时返回 null，调用方需要根据工具需求自行决定是否报错。
        return HOLDER.get();
    }

    /**
     * 清理当前线程上的上下文，防止下一个请求读到旧身份或旧会话数据。
     */
    public static void clear() {
        // remove 而不是 set(null)，彻底清掉 ThreadLocalMap 中的值，降低线程复用泄漏风险。
        HOLDER.remove();
    }
}
