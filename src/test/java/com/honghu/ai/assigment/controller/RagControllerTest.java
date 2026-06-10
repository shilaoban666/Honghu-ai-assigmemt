package com.honghu.ai.assigment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.config.properties.AwsProperties;
import com.honghu.ai.assigment.dto.RagFileStatusResponse;
import com.honghu.ai.assigment.dto.RegisterUploadedFileRequest;
import com.honghu.ai.assigment.dto.UploadUrlRequest;
import com.honghu.ai.assigment.dto.record.PresignedUploadResult;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.exception.RagAccessDeniedException;
import com.honghu.ai.assigment.rag.security.RagAccessGuard;
import com.honghu.ai.assigment.service.RagDocumentProcessService;
import com.honghu.ai.assigment.service.RagFileRegistrationService;
import com.honghu.ai.assigment.rag.monitor.RagFileStatusStreamer;
import com.honghu.ai.assigment.rag.monitor.RagIngestionStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RagController} 的 MockMvc 测试。
 *
 * <p>采用 standalone 配置而不是 @WebMvcTest：</p>
 * <ul>
 *     <li>不需要加载完整 Spring Context，跑得快</li>
 *     <li>避免依赖 RagController 之外的 controller bean 启动</li>
 *     <li>错误响应由 ResponseStatusException 默认处理（status 正确，body 为空也 OK）</li>
 * </ul>
 *
 * <p>覆盖的接口与场景：</p>
 * <ol>
 *     <li>POST /api/v1/rag/{userId}/upload-url
 *         <ul><li>缺 X-User-Id → 401</li>
 *         <li>header != path userId → 403</li>
 *         <li>正常路径 → 200 返回 uploadUrl/objectKey</li></ul></li>
 *     <li>POST /api/v1/rag/files
 *         <ul><li>service 抛 RagAccessDeniedException → 403</li>
 *         <li>service 抛 IllegalArgumentException → 400</li>
 *         <li>正常路径 → 201 + status response</li></ul></li>
 *     <li>GET /api/v1/rag/files/{fileId}/status
 *         <ul><li>缺 header → 401</li>
 *         <li>正常路径 → 200 + status</li>
 *         <li>NoSuchElementException → 404</li></ul></li>
 *     <li>GET /api/v1/rag/sessions/{sessionId}/files
 *         <ul><li>跨用户 → 403</li>
 *         <li>正常 → 200 + list</li></ul></li>
 * </ol>
 */
class RagControllerTest {

    private static final String CALLER_USER_ID = "u-001";
    private static final String CALLER_USERNAME = "alice";
    private static final String OBJECT_KEY = "alice/sess-1/pdf/file-001/report.pdf";

    private RagFileRegistrationService ragFileRegistrationService;
    private RagIngestionStatusService ragIngestionStatusService;
    private RagFileStatusStreamer ragFileStatusStreamer;
    private RagAccessGuard ragAccessGuard;
    private RagDocumentProcessService ragDocumentProcessService;
    private AwsProperties awsProperties;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ragFileRegistrationService = mock(RagFileRegistrationService.class);
        ragIngestionStatusService = mock(RagIngestionStatusService.class);
        ragFileStatusStreamer = mock(RagFileStatusStreamer.class);
        ragAccessGuard = mock(RagAccessGuard.class);
        ragDocumentProcessService = mock(RagDocumentProcessService.class);
        awsProperties = new AwsProperties();
        awsProperties.getS3().setPresignedUrlExpirationMinutes(15);
        objectMapper = new ObjectMapper();

        RagController controller = new RagController(
                ragFileRegistrationService, ragIngestionStatusService,
                ragFileStatusStreamer, ragAccessGuard, ragDocumentProcessService, awsProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ==========================================================
    // 1) /upload-url
    // ==========================================================

    @Test
    void uploadUrlShouldReturn401WhenHeaderMissing() throws Exception {
        // Spring MVC 在请求级别不带 X-User-Id 时会先报 missing header 400 / 由我们统一
        // 设置 required=false，所以走到 controller 内部 → guard 抛 RagAccessDeniedException → 401
        when(ragAccessGuard.requireSameUser(null, CALLER_USER_ID))
                .thenThrow(new RagAccessDeniedException("缺少调用者 userId 请求头"));

        UploadUrlRequest body = UploadUrlRequest.builder()
                .sessionId("sess-1").fileType("pdf").fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/{userId}/upload-url", CALLER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadUrlShouldReturn403WhenHeaderUserMismatchesPathUser() throws Exception {
        when(ragAccessGuard.requireSameUser("attacker", CALLER_USER_ID))
                .thenThrow(new RagAccessDeniedException("无权代表该用户操作"));

        UploadUrlRequest body = UploadUrlRequest.builder()
                .sessionId("sess-1").fileType("pdf").fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/{userId}/upload-url", CALLER_USER_ID)
                        .header("X-User-Id", "attacker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        verify(ragDocumentProcessService, never()).generatePresignedUploadUrl(eq(CALLER_USER_ID), eq("sess-1"), eq("pdf"), eq("report.pdf"));
    }

    @Test
    void uploadUrlShouldReturn200AndDtoWhenAuthorized() throws Exception {
        when(ragAccessGuard.requireSameUser(CALLER_USER_ID, CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragDocumentProcessService.generatePresignedUploadUrl(CALLER_USER_ID, "sess-1", "pdf", "report.pdf"))
                .thenReturn(new PresignedUploadResult("https://s3/xxx", OBJECT_KEY, "application/pdf", "file-001"));

        UploadUrlRequest body = UploadUrlRequest.builder()
                .sessionId("sess-1").fileType("pdf").fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/{userId}/upload-url", CALLER_USER_ID)
                        .header("X-User-Id", CALLER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://s3/xxx"))
                .andExpect(jsonPath("$.objectKey").value(OBJECT_KEY))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileId").value("file-001"))
                .andExpect(jsonPath("$.fileType").value("pdf"))
                .andExpect(jsonPath("$.expirationMinutes").value(15));
    }

    // ==========================================================
    // 2) /files (register)
    // ==========================================================

    @Test
    void registerShouldReturn403WhenServiceThrowsAccessDenied() throws Exception {
        when(ragFileRegistrationService.registerUploadedFile(eq(CALLER_USER_ID), eq(OBJECT_KEY), eq("report.pdf")))
                .thenThrow(new RagAccessDeniedException("无权登记其他用户路径下的文件"));

        RegisterUploadedFileRequest req = RegisterUploadedFileRequest.builder()
                .objectKey(OBJECT_KEY).fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/files")
                        .header("X-User-Id", CALLER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerShouldReturn400WhenServiceThrowsIllegalArgument() throws Exception {
        when(ragFileRegistrationService.registerUploadedFile(eq(CALLER_USER_ID), eq(OBJECT_KEY), eq("report.pdf")))
                .thenThrow(new IllegalArgumentException("不支持的文件类型"));

        RegisterUploadedFileRequest req = RegisterUploadedFileRequest.builder()
                .objectKey(OBJECT_KEY).fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/files")
                        .header("X-User-Id", CALLER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerShouldReturn201AndStatusBodyWhenSuccess() throws Exception {
        when(ragFileRegistrationService.registerUploadedFile(eq(CALLER_USER_ID), eq(OBJECT_KEY), eq("report.pdf")))
                .thenReturn("file-001");
        when(ragIngestionStatusService.getFileStatus(CALLER_USER_ID, "file-001"))
                .thenReturn(stubStatus("RECEIVED"));
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());

        RegisterUploadedFileRequest req = RegisterUploadedFileRequest.builder()
                .objectKey(OBJECT_KEY).fileName("report.pdf").build();

        mockMvc.perform(post("/api/v1/rag/files")
                        .header("X-User-Id", CALLER_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.fileId").value("file-001"));
    }

    // ==========================================================
    // 3) /files/{fileId}/status
    // ==========================================================

    @Test
    void getFileStatusShouldReturn401WhenHeaderMissing() throws Exception {
        when(ragAccessGuard.requireUser(null))
                .thenThrow(new RagAccessDeniedException("缺少调用者 userId 请求头"));

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/status", "file-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getFileStatusShouldReturn200WhenAuthorized() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragIngestionStatusService.getFileStatus(CALLER_USER_ID, "file-001"))
                .thenReturn(stubStatus("INDEXED"));

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/status", "file-001")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentStatus").value("INDEXED"))
                .andExpect(jsonPath("$.fileId").value("file-001"));
    }

    @Test
    void getFileStatusShouldReturn404WhenFileMissing() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragIngestionStatusService.getFileStatus(CALLER_USER_ID, "missing"))
                .thenThrow(new NoSuchElementException("未找到文件状态"));

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/status", "missing")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isNotFound());
    }

    // ==========================================================
    // 4) /sessions/{sessionId}/files
    // ==========================================================

    @Test
    void listSessionFilesShouldReturn403WhenServiceRejects() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragIngestionStatusService.listSessionFiles(CALLER_USER_ID, "sess-other"))
                .thenThrow(new RagAccessDeniedException("无权访问该会话"));

        mockMvc.perform(get("/api/v1/rag/sessions/{sessionId}/files", "sess-other")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void listSessionFilesShouldReturn200AndArrayWhenAuthorized() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragIngestionStatusService.listSessionFiles(CALLER_USER_ID, "sess-1"))
                .thenReturn(List.of(stubStatus("INDEXED")));

        mockMvc.perform(get("/api/v1/rag/sessions/{sessionId}/files", "sess-1")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentStatus").value("INDEXED"));
    }

    // ==========================================================
    // 5) /files/{fileId}/download-url
    // ==========================================================

    @Test
    void downloadUrlShouldReturn200WhenAuthorized() throws Exception {
        when(ragDocumentProcessService.generatePresignedDownloadUrl(CALLER_USER_ID, "file-001", null))
                .thenReturn("https://s3/download-001");

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/download-url", "file-001")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value("https://s3/download-001"))
                .andExpect(jsonPath("$.expiresInSeconds").value(900));
    }

    @Test
    void downloadUrlShouldReturn403WhenAccessDenied() throws Exception {
        when(ragDocumentProcessService.generatePresignedDownloadUrl(CALLER_USER_ID, "file-001", null))
                .thenThrow(new RagAccessDeniedException("无权访问该文件"));

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/download-url", "file-001")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isForbidden());
    }

    // ==========================================================
    // 6) /files/{fileId}/status/stream  (SSE)
    // ==========================================================

    @Test
    void streamShouldReturn403WhenAccessDeniedBeforeStreaming() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        when(ragFileStatusStreamer.stream(eq(CALLER_USER_ID), eq("file-001"), anyLong(), anyLong()))
                .thenThrow(new RagAccessDeniedException("无权访问"));

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/status/stream", "file-001")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void streamShouldReturn200AndTextEventStreamWhenAuthorized() throws Exception {
        when(ragAccessGuard.requireUser(CALLER_USER_ID))
                .thenReturn(User.builder().userId(CALLER_USER_ID).username(CALLER_USERNAME).build());
        // Streamer 返回一个空 SseEmitter，立即 complete，让 MockMvc 不挂起。
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        emitter.complete();
        when(ragFileStatusStreamer.stream(eq(CALLER_USER_ID), eq("file-001"), anyLong(), anyLong()))
                .thenReturn(emitter);

        mockMvc.perform(get("/api/v1/rag/files/{fileId}/status/stream", "file-001")
                        .header("X-User-Id", CALLER_USER_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    private static RagFileStatusResponse stubStatus(String docStatus) {
        return RagFileStatusResponse.builder()
                .fileId("file-001")
                .sessionId("sess-1")
                .fileName("report.pdf")
                .fileType("pdf")
                .documentStatus(docStatus)
                .completed("INDEXED".equals(docStatus))
                .availableForChat("INDEXED".equals(docStatus))
                .build();
    }
}
