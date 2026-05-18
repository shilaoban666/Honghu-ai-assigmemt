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

class LiquibaseYamlChangelogParseTest {

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
            assertThat(changeSets).hasSize(21);
            assertThat(changeSets)
                    .extracting(ChangeSet::getId)
                    .containsExactly(
                            "1",
                            "2",
                            "3",
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
                            "rag-8-add-document-chat-id"
                    );
            assertThat(changeSets)
                    .extracting(ChangeSet::getFilePath)
                    .contains(
                            "DB/changelog/db.changelog-master.xml",
                            "DB/changelog/db.changelog-users.xml",
                            "DB/changelog/db.changelog-ai-task-keywords.xml",
                            "DB/changelog/db.changelog-memory-summary.xml",
                            "DB/changelog/db.changelog-ai-models.xml",
                            "DB/changelog/db.changelog-rag.xml"
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
