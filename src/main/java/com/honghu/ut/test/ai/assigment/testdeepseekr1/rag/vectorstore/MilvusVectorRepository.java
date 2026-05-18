package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.vectorstore;

import com.alibaba.fastjson.JSONObject;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.emebding.RagEmbeddingService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量库写入仓储。
 *
 * <p>这个类是我们自己的 Milvus 写入边界，职责非常明确：</p>
 * <ul>
 *     <li>不调用 Spring AI 的 {@code VectorStore.add()}；</li>
 *     <li>不在这里生成 embedding，embedding 由 {@link RagEmbeddingService} 负责；</li>
 *     <li>只接收已经生成好的向量，使用 Milvus Java SDK 直接 delete / insert；</li>
 *     <li>collection schema 与 Spring AI MilvusVectorStore 保持兼容，方便检索侧暂时继续使用 Spring AI。</li>
 * </ul>
 *
 * <p>当前字段名刻意沿用 Spring AI 内部实现：</p>
 * <ul>
 *     <li>{@code doc_id}：主键，存稳定向量 id，例如 {@code documentId:chunkIndex}；</li>
 *     <li>{@code content}：chunk 原文，检索命中后可直接回填上下文；</li>
 *     <li>{@code metadata}：JSON 元数据，存 documentId、sessionId、fileId 等过滤/调试信息；</li>
 *     <li>{@code embedding}：FloatVector，存真正的 dense embedding。</li>
 * </ul>
 *
 * <p>Milvus 2.3 Java SDK 这里没有使用高级 upsert API。为了让重建幂等，调用方会先按稳定
 * {@code doc_id} 删除旧向量，再调用 {@link #insert(List)} 写入新向量。这与 Spring AI
 * {@code MilvusVectorStore#doAdd(...)} 的底层插入方式一致，只是 embedding 不再藏在 add() 内部。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MilvusVectorRepository implements InitializingBean {

    /** Milvus schema 中的主键列名。 */
    public static final String DOC_ID_FIELD_NAME = "doc_id";
    /** Milvus schema 中保存 chunk 原文的列名。 */
    public static final String CONTENT_FIELD_NAME = "content";
    /** Milvus schema 中保存 JSON 元数据的列名。 */
    public static final String METADATA_FIELD_NAME = "metadata";
    /** Milvus schema 中保存 dense embedding 的向量列名。 */
    public static final String EMBEDDING_FIELD_NAME = "embedding";

    /** doc_id 列最大长度。 */
    private static final int DOC_ID_MAX_LENGTH = 36;
    /** content 列最大长度。 */
    private static final int CONTENT_MAX_LENGTH = 65535;

    private final MilvusServiceClient milvusClient;
    private final MilvusVectorStoreProperties properties;

    /**
     * 应用启动时是否初始化 Milvus schema，完全尊重
     * {@code spring.ai.vectorstore.milvus.initialize-schema}。
     *
     * <p>生产环境通常建议用 migration/运维脚本提前建 collection，所以你现在的
     * {@code initialize-schema=false} 是合理的。这里保留初始化能力，是为了本地开发或新环境
     * 可以一键创建与当前代码兼容的 collection。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (properties.isInitializeSchema()) {
            createCollectionIfNecessary();
        }
    }

    /**
     * 按稳定向量 id 删除旧数据。
     *
     * <p>Milvus 的 delete 使用表达式语法，因此这里会把 id 列表拼成：
     * {@code doc_id in ['1:0','1:1']}。调用方传进来的 id 来自我们自己的
     * {@code documentId:chunkIndex}，正常不会包含引号；这里仍做转义，防止未来 id 规则变化后
     * 留下表达式注入或语法错误。</p>
     *
     * @param ids 要删除的向量主键列表
     * @return Milvus 报告的删除数量
     */
    public long deleteByIds(List<String> ids) {
        // 没有要删的主键时直接返回 0，避免发无意义请求到 Milvus。
        if (CollectionUtils.isEmpty(ids)) {
            return 0L;
        }

        // 组装 Milvus delete 表达式，例如：doc_id in ['1:0','1:1']。
        String deleteExpression = String.format("%s in [%s]",
                DOC_ID_FIELD_NAME,
                ids.stream()
                        .map(this::quoteMilvusString)
                        .collect(Collectors.joining(",")));

        // 构造删除请求，指定 collection 与表达式条件。
        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr(deleteExpression)
                .build();

        // 调用 Milvus SDK 执行删除，并统一做响应成功校验。
        R<MutationResult> response = milvusClient.delete(deleteParam);
        ensureSuccess(response, "delete vectors from Milvus");

        // 从 Milvus 返回值中提取删除计数，用于日志和诊断。
        MutationResult result = response.getData();
        long deleteCount = result == null ? 0L : result.getDeleteCnt();
        if (deleteCount != ids.size()) {
            log.debug("Milvus vector delete count differs from requested count: requested={}, deleted={}, collection={}",
                    ids.size(),
                    deleteCount,
                    properties.getCollectionName());
        }
        return deleteCount;
    }

    /**
     * 批量插入已经完成 embedding 的向量记录。
     *
     * <p>这里使用 Milvus 的“按列插入”方式，与 Spring AI 内部实现一致。四个数组的顺序必须完全一致：
     * 第 n 个 doc_id、content、metadata、embedding 共同组成第 n 行数据。相比逐行 insert，
     * 按列批量插入的网络开销更低，也更接近 Milvus SDK 的推荐写法。</p>
     *
     * @param records 已经携带 embedding 的向量记录
     * @return Milvus 报告的插入数量
     */
    public long insert(List<VectorRecord> records) {
        // 没有记录可写时直接返回 0，减少不必要的 SDK 调用。
        if (CollectionUtils.isEmpty(records)) {
            return 0L;
        }

        // 下面四个集合分别对应 Milvus 的四列数据；同一索引位置表示同一行。
        List<String> docIds = new ArrayList<>(records.size());
        List<String> contents = new ArrayList<>(records.size());
        List<JSONObject> metadata = new ArrayList<>(records.size());
        List<List<Float>> embeddings = new ArrayList<>(records.size());

        for (VectorRecord record : records) {
            // 插入前先做本地校验，尽量在进入 SDK 前就把坏数据挡住。
            validateRecord(record);
            docIds.add(record.id());
            contents.add(record.content());
            metadata.add(new JSONObject(record.metadata() == null ? Map.of() : record.metadata()));
            embeddings.add(toFloatList(record.embedding()));
        }

        // 按列构造 insert 字段列表，列名必须与 collection schema 完全一致。
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(DOC_ID_FIELD_NAME, docIds));
        fields.add(new InsertParam.Field(CONTENT_FIELD_NAME, contents));
        fields.add(new InsertParam.Field(METADATA_FIELD_NAME, metadata));
        fields.add(new InsertParam.Field(EMBEDDING_FIELD_NAME, embeddings));

        // 组装 Milvus 插入请求，指定库名、集合名和所有列数据。
        InsertParam insertParam = InsertParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .withFields(fields)
                .build();

        // 执行插入并检查响应状态。
        R<MutationResult> response = milvusClient.insert(insertParam);
        ensureSuccess(response, "insert vectors into Milvus");

        // 如果 Milvus 没明确返回插入数，就保守地以请求记录数作为插入数量。
        MutationResult result = response.getData();
        long insertCount = result == null ? records.size() : result.getInsertCnt();
        log.info("Milvus vectors inserted: collection={}, count={}, dimensions={}",
                properties.getCollectionName(),
                insertCount,
                properties.getEmbeddingDimension());
        return insertCount;
    }

    /**
     * 创建 collection、index 并加载 collection。
     *
     * <p>这段逻辑参考 Spring AI {@code MilvusVectorStore#createCollection()}：</p>
     * <ol>
     *     <li>检查 collection 是否存在；不存在则创建四列 schema；</li>
     *     <li>检查 embedding 字段是否已有 index；没有则创建 index；</li>
     *     <li>调用 loadCollection，让后续 search/insert 处在可用状态。</li>
     * </ol>
     */
    public void createCollectionIfNecessary() {
        // collection 不存在时先创建；已存在则只补 index 与 load。
        if (!collectionExists()) {
            createCollection();
        }
        createIndexIfNecessary();
        loadCollection();
    }

    private boolean collectionExists() {
        // 先问 Milvus 当前 collection 是否存在，避免重复创建。
        R<Boolean> response = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());
        ensureSuccess(response, "check Milvus collection existence");
        return Boolean.TRUE.equals(response.getData());
    }

    private void createCollection() {
        // 依次定义四个字段的 schema：主键、正文、JSON 元数据、向量列。
        FieldType docIdField = FieldType.newBuilder()
                .withName(DOC_ID_FIELD_NAME)
                .withDataType(DataType.VarChar)
                .withMaxLength(DOC_ID_MAX_LENGTH)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType contentField = FieldType.newBuilder()
                .withName(CONTENT_FIELD_NAME)
                .withDataType(DataType.VarChar)
                .withMaxLength(CONTENT_MAX_LENGTH)
                .build();

        FieldType metadataField = FieldType.newBuilder()
                .withName(METADATA_FIELD_NAME)
                .withDataType(DataType.JSON)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName(EMBEDDING_FIELD_NAME)
                .withDataType(DataType.FloatVector)
                .withDimension(properties.getEmbeddingDimension())
                .build();

        // 组装 collection 创建参数，包含描述、强一致性、分片数和全部字段定义。
        CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .withDescription("RAG chunks vector collection")
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withShardsNum(2)
                .addFieldType(docIdField)
                .addFieldType(contentField)
                .addFieldType(metadataField)
                .addFieldType(embeddingField)
                .build();

        // 真正向 Milvus 发起建表请求。
        R<RpcStatus> response = milvusClient.createCollection(createCollectionParam);
        ensureSuccess(response, "create Milvus collection");
        log.info("Milvus collection created: database={}, collection={}, dimensions={}",
                properties.getDatabaseName(),
                properties.getCollectionName(),
                properties.getEmbeddingDimension());
    }

    private void createIndexIfNecessary() {
        // 先检查 embedding 字段是否已经有索引；有的话就不重复创建。
        R<DescribeIndexResponse> response = milvusClient.describeIndex(DescribeIndexParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());

        if (response.getException() == null && response.getData() != null) {
            return;
        }

        // 没有索引时，根据 Spring AI 配置里的 indexType / metricType 建一个兼容索引。
        R<RpcStatus> createIndexResponse = milvusClient.createIndex(CreateIndexParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .withFieldName(EMBEDDING_FIELD_NAME)
                .withIndexType(IndexType.valueOf(properties.getIndexType().name()))
                .withMetricType(MetricType.valueOf(properties.getMetricType().name()))
                .withExtraParam(properties.getIndexParameters())
                .withSyncMode(Boolean.FALSE)
                .build());
        ensureSuccess(createIndexResponse, "create Milvus vector index");
        log.info("Milvus vector index created: collection={}, field={}, indexType={}, metricType={}",
                properties.getCollectionName(),
                EMBEDDING_FIELD_NAME,
                properties.getIndexType(),
                properties.getMetricType());
    }

    private void loadCollection() {
        // 加载 collection 到可检索状态，确保后续 search / insert 路径可正常工作。
        R<RpcStatus> response = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());
        ensureSuccess(response, "load Milvus collection");
    }

    private void validateRecord(VectorRecord record) {
        // 第一层：record 自身不能为空。
        if (record == null) {
            throw new IllegalArgumentException("Milvus vector record must not be null");
        }
        // 第二层：主键必须存在且长度合法。
        if (!StringUtils.hasText(record.id())) {
            throw new IllegalArgumentException("Milvus vector id must not be blank");
        }
        if (record.id().length() > DOC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("Milvus vector id is too long: id="
                    + record.id() + ", maxLength=" + DOC_ID_MAX_LENGTH);
        }
        // 第三层：正文必须非空，且不能超过 schema 限制长度。
        if (!StringUtils.hasText(record.content())) {
            throw new IllegalArgumentException("Milvus vector content must not be blank: id=" + record.id());
        }
        if (record.content().length() > CONTENT_MAX_LENGTH) {
            throw new IllegalArgumentException("Milvus vector content is too long: id="
                    + record.id() + ", maxLength=" + CONTENT_MAX_LENGTH);
        }
        // 第四层：embedding 必须存在，且维度必须与当前 collection 约定完全一致。
        if (record.embedding() == null || record.embedding().length == 0) {
            throw new IllegalArgumentException("Milvus vector embedding must not be empty: id=" + record.id());
        }
        if (record.embedding().length != properties.getEmbeddingDimension()) {
            throw new IllegalArgumentException("Milvus vector dimension mismatch: id="
                    + record.id()
                    + ", expected=" + properties.getEmbeddingDimension()
                    + ", actual=" + record.embedding().length);
        }
    }

    private List<Float> toFloatList(float[] vector) {
        // SDK 这里需要的是 List<Float>，所以把原始 float[] 转成装箱列表。
        List<Float> result = new ArrayList<>(vector.length);
        for (float value : vector) {
            result.add(value);
        }
        return result;
    }

    private String quoteMilvusString(String value) {
        // 对反斜杠和单引号做基本转义，避免 delete 表达式被截断或注入。
        String escaped = value
                .replace("\\", "\\\\")
                .replace("'", "\\'");
        return "'" + escaped + "'";
    }

    private void ensureSuccess(R<?> response, String action) {
        // SDK 如果返回 null，说明调用链路已经异常，不允许继续往下走。
        if (response == null) {
            throw new IllegalStateException("Milvus response is null when trying to " + action);
        }
        // 优先检查底层是否抛了异常对象。
        if (response.getException() != null) {
            throw new IllegalStateException("Failed to " + action, response.getException());
        }
        // 再检查业务状态码是否为 Success；否则拼出清晰错误信息。
        if (response.getStatus() != null && response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException("Failed to " + action
                    + ": status=" + response.getStatus()
                    + ", message=" + response.getMessage());
        }
    }

    /**
     * 一条准备写入 Milvus 的向量记录。
     *
     * @param id 稳定主键，当前使用 {@code documentId:chunkIndex}
     * @param content chunk 原文
     * @param metadata JSON 元数据，供过滤、调试和检索结果回填使用
     * @param embedding 已经生成好的 dense embedding
     */
    public record VectorRecord(String id, String content, Map<String, Object> metadata, float[] embedding) {
    }
}
