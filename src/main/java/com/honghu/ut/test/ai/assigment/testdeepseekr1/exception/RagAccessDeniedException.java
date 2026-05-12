package com.honghu.ut.test.ai.assigment.testdeepseekr1.exception;

/**
 * RAG 链路上的越权异常。
 *
 * <p>用一个项目本地的运行期异常，避免依赖 spring-security-core
 * （当前 pom 只引入了 spring-security-crypto，没有完整的 Security 栈）。</p>
 *
 * <p>调用方约定：</p>
 * <ul>
 *     <li>service 层负责发现"调用者无权访问该资源"时抛出此异常</li>
 *     <li>controller 层捕获后翻译成 HTTP 403，且不回显原始 message</li>
 * </ul>
 */
public class RagAccessDeniedException extends RuntimeException {
    public RagAccessDeniedException(String message) {
        super(message);
    }
}
