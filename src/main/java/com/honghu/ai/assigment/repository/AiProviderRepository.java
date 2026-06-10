package com.honghu.ai.assigment.repository;

import com.honghu.ai.assigment.entity.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AI Provider 注册表仓库。
 */
public interface AiProviderRepository extends JpaRepository<AiProvider, String> {

    /** 按是否启用筛选，供后台列表与运行期解析使用。 */
    List<AiProvider> findByEnabledTrue();
}
