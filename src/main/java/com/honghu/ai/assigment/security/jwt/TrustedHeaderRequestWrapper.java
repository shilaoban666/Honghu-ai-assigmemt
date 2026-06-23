package com.honghu.ai.assigment.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Enumeration;

/**
 * 把可信 userId 覆盖进 {@code X-User-Id} 请求头的包装器。
 *
 * <p>历史代码大量直接用 {@code @RequestHeader("X-User-Id")} 读身份（ChatController / RagController 等）。
 * 为了在不改每个 controller 签名的前提下消除“前端随便填 userId 就能越权”的问题，
 * JWT 过滤器在验签成功后用本包装器把 {@code X-User-Id} 强制改写成 token 里的可信 userId；
 * 当没有有效 JWT 且关闭了 dev 兜底时，则把该头直接抹掉（trustedUserId 传 null），
 * 让下游按“匿名/游客”处理，而不是误信伪造头。</p>
 *
 * <p>注意：只接管身份头 {@code X-User-Id}。{@code X-Workspace-Id} 仍透传——它表示“本次请求选择哪个
 * 工作空间”，由后端结合可信 userId 校验成员关系，不属于身份伪造面。</p>
 */
public class TrustedHeaderRequestWrapper extends HttpServletRequestWrapper {

    public static final String USER_ID_HEADER = "X-User-Id";

    /** 可信 userId；为 null 表示“抹掉该头”。 */
    private final String trustedUserId;

    public TrustedHeaderRequestWrapper(HttpServletRequest request, String trustedUserId) {
        super(request);
        this.trustedUserId = trustedUserId;
    }

    @Override
    public String getHeader(String name) {
        if (USER_ID_HEADER.equalsIgnoreCase(name)) {
            return StringUtils.hasText(trustedUserId) ? trustedUserId : null;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (USER_ID_HEADER.equalsIgnoreCase(name)) {
            return StringUtils.hasText(trustedUserId)
                    ? Collections.enumeration(Collections.singletonList(trustedUserId))
                    : Collections.emptyEnumeration();
        }
        return super.getHeaders(name);
    }
}
