package com.honghu.ai.assigment.controller;

import com.honghu.ai.assigment.rag.RagVectorBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/**
 * RAG 管理接口。
 *
 * <p>它不是给普通前端业务流量调用的，而是给运维/管理员做迁移与修复操作的入口。
 * 当前只开放“历史文档向量回填”这一项管理动作。</p>
 */
@RestController
@RequestMapping("/api/v1/rag/admin")
@RequiredArgsConstructor
public class RagBackFillController {

	private final RagVectorBackfillService backfillService;

	/**
	 * 手动触发历史向量回填。
	 *
	 * <p>之所以提供管理接口而不是在应用启动时自动执行，有两个考虑：</p>
	 * <ol>
	 *     <li>历史数据量可能很大，自动回填会拖慢甚至阻塞应用启动</li>
	 *     <li>回填涉及外部依赖（DashScope / Milvus），手动触发更利于在低峰期观察与止损</li>
	 * </ol>
	 */
	@PostMapping("/vector/backfill")
	public ResponseEntity<RagVectorBackfillService.BackfillReport> backfill(
			@RequestHeader("X-Admin-Token") String token) {
		String expectedToken = System.getenv("RAG_ADMIN_TOKEN");

		// 当前先用最小可行的管理口鉴权：
		// 只有请求头 token 与环境变量完全一致才允许触发，避免误操作或被普通业务流量误调。
		if (!StringUtils.hasText(expectedToken) || !Objects.equals(token, expectedToken)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}

		// 回填结果直接返回给调用方，便于运维侧观察“处理了多少文档 / 分片 / 失败数”。
		return ResponseEntity.ok(backfillService.backfillAll());
	}
}


