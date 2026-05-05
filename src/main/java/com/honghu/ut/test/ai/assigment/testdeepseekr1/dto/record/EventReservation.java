package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record;


import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.RagIngestionEvent;
/**
 * @program: testDeepseekR1
 * @description:
 * @author: shilaoban
 * @create: 2026-05-01 00:09
 * 事件预占结果。
 *
 * <p>要么拿到 event 继续处理，要么直接给出一个上层可返回的结果。</p>
 */
public record EventReservation(RagIngestionEvent event, IngestionResult result) {
}

