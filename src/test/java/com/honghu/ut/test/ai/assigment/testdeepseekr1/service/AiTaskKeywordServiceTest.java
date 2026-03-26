package com.honghu.ut.test.ai.assigment.testdeepseekr1.service;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.AiTaskKeyword;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.AiTaskKeywordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTaskKeywordServiceTest {

    @Mock
    private AiTaskKeywordRepository aiTaskKeywordRepository;

    @InjectMocks
    private AiTaskKeywordService aiTaskKeywordService;

    @Test
    void shouldLoadKeywordsFromDatabaseFirst() {
        when(aiTaskKeywordRepository.findByEnabledTrueOrderByPriorityAscIdAsc()).thenReturn(List.of(
                AiTaskKeyword.builder().id(1L).taskType(AiTaskKeywordService.TASK_TYPE_CODE).keyword("java").enabled(true).priority(1).build(),
                AiTaskKeyword.builder().id(2L).taskType(AiTaskKeywordService.TASK_TYPE_TEXT).keyword("润色").enabled(true).priority(2).build()
        ));

        aiTaskKeywordService.refreshCache();

        Optional<AiTaskKeywordService.TaskKeywordMatch> match = aiTaskKeywordService.findFirstMatch("请帮我优化这段java代码");
        Map<String, List<String>> grouped = aiTaskKeywordService.getKeywordsGroupedByTaskType();

        assertThat(match).isPresent();
        assertThat(match.get().taskType()).isEqualTo(AiTaskKeywordService.TASK_TYPE_CODE);
        assertThat(grouped).containsKey(AiTaskKeywordService.TASK_TYPE_CODE);
        assertThat(grouped.get(AiTaskKeywordService.TASK_TYPE_CODE)).contains("java");
    }

    @Test
    void shouldFallbackToBuiltInKeywordsWhenDatabaseIsEmpty() {
        when(aiTaskKeywordRepository.findByEnabledTrueOrderByPriorityAscIdAsc()).thenReturn(List.of());

        aiTaskKeywordService.refreshCache();

        Optional<AiTaskKeywordService.TaskKeywordMatch> match = aiTaskKeywordService.findFirstMatch("请帮我做一下系统架构设计");

        assertThat(match).isPresent();
        assertThat(match.get().taskType()).isEqualTo(AiTaskKeywordService.TASK_TYPE_DESIGN);
        assertThat(aiTaskKeywordService.isComplexTaskType(match.get().taskType())).isTrue();
    }
}

