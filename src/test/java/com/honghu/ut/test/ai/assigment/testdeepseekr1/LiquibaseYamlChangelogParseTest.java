package com.honghu.ut.test.ai.assigment.testdeepseekr1;

import liquibase.changelog.ChangeSet;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Liquibase 已经切换到 YAML-first，但历史 changeSet 身份仍保持兼容。
 *
 * <p>这个测试重点不是验证数据库能不能连通，而是验证两件非常关键的迁移约束：</p>
 * <ol>
 *     <li>源码目录里已经没有物理 XML changelog 文件。</li>
 *     <li>YAML 解析后的 changeSet.filePath 仍保持历史 logicalFilePath，不破坏 DATABASECHANGELOG 身份。</li>
 * </ol>
 */
class LiquibaseYamlChangelogParseTest {

    /**
     * 解析 YAML 主 changelog，并确认历史 XML 逻辑路径仍被保留。
     *
     * <p>如果这个测试失败，通常意味着两类风险之一：</p>
     * <ul>
     *     <li>有人又把物理 XML 文件放回源码目录，导致 YAML-first 约定被破坏。</li>
     *     <li>有人误改了 logicalFilePath，使已执行 changeSet 的 filename / checksum 归属发生漂移。</li>
     * </ul>
     */
    @Test
    void yamlMasterChangelogCanBeParsedWithoutPhysicalXmlFilesAndStillKeepOriginalLogicalFilePaths() throws Exception {
        try (ClassLoaderResourceAccessor resourceAccessor = new ClassLoaderResourceAccessor()) {
            // 运行时已经切到 YAML-first，这里额外确认仓库中不再依赖物理 XML changelog 文件。
            // 注意：下面断言“物理 XML 文件不存在”，并不意味着 Liquibase 历史身份改成了 YAML；
            // 真正决定 DATABASECHANGELOG.filename / checksum 归属的仍然是各 YAML 文件里的 logicalFilePath。
            assertThat(Files.exists(Path.of("src/main/resources/DB/changelog/db.changelog-master.xml"))).isFalse();
            assertThat(Files.exists(Path.of("src/main/resources/DB/changelog/db.changelog-rag.xml"))).isFalse();

            DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance()
                    .getParser("DB/changelog/db.changelog-master.yaml", resourceAccessor)
                    .parse("DB/changelog/db.changelog-master.yaml", new ChangeLogParameters(), resourceAccessor);

            List<ChangeSet> changeSets = changeLog.getChangeSets();
            assertThat(changeSets)
                    .extracting(ChangeSet::getId)
                    .contains(
                            "1",
                            "2",
                            "3",
                            "3-20260519-user-avatar",
                            "4",
                            "5",
                            "6",
                            "7",
                            "8",
                            "9",
                            "9.1",
                            "9.2",
                            "10",
                            "rag-1-create-document-table",
                            "rag-2-create-document-chunk-table",
                            "rag-3-create-ingestion-event-table",
                            "rag-4-rename-document-file-status-to-status",
                            "rag-5-rename-event-status-to-file-status",
                            "rag-6-add-document-file-id-index",
                            "rag-7-add-event-object-key-index",
                            "rag-9-add-document-chunk-metadata",
                            "rag-8-add-document-chat-id",
                            "billing-roles-tenancy-1",
                            "billing-roles-tenancy-2",
                            "billing-roles-tenancy-3",
                            "billing-roles-tenancy-4",
                            "billing-roles-tenancy-5",
                            "billing-roles-tenancy-6",
                            "billing-roles-tenancy-7",
                            "billing-roles-tenancy-8",
                            "billing-roles-tenancy-9",
                            "billing-roles-tenancy-10",
                            "billing-roles-tenancy-11",
                            "skills-001-create-skill-tables",
                            "skills-002-seed-marketplace",
                            "skills-003-seed-capability-kinds"
                    );
            assertThat(changeSets)
                    .extracting(ChangeSet::getFilePath)
                    .contains(
                            "DB/changelog/db.changelog-master.xml",
                            "DB/changelog/db.changelog-users.xml",
                            "DB/changelog/db.changelog-ai-task-keywords.xml",
                            "DB/changelog/db.changelog-memory-summary.xml",
                            "DB/changelog/db.changelog-ai-models.xml",
                            "DB/changelog/db.changelog-rag.xml",
                            "DB/changelog/db.changelog-billing-roles-tenancy.yaml",
                            "DB/changelog/db.changelog-skills.yaml"
                    );

            ChangeSet ragMetadataChangeSet = changeSets.stream()
                    .filter(changeSet -> "rag-9-add-document-chunk-metadata".equals(changeSet.getId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(ragMetadataChangeSet.getFilePath()).isEqualTo("DB/changelog/db.changelog-rag.xml");
            assertThat(ragMetadataChangeSet.getChanges()).hasSize(1);
        }
    }
}
