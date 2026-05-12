package com.honghu.ut.test.ai.assigment.testdeepseekr1.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.dto.record.S3UploadReceivedMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.rag.document.ingest.RagEventMessageParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEventMessageParserTest {

    private final RagEventMessageParser parser = new RagEventMessageParser(new ObjectMapper());

    @Test
    void shouldParseDirectS3EventMessage() throws Exception {
        String rawMessage = """
                {
                  "Records": [
                    {
                      "eventName": "ObjectCreated:Put",
                      "s3": {
                        "bucket": { "name": "bucket-a" },
                        "object": {
                          "key": "admin/sess-1/pdf/file-1/report%20v1.pdf",
                          "eTag": "etag-001",
                          "size": 123,
                          "sequencer": "seq-001"
                        }
                      }
                    }
                  ]
                }
                """;

        List<S3UploadReceivedMessage> messages = parser.parse(rawMessage);

        assertEquals(1, messages.size());
        S3UploadReceivedMessage message = messages.get(0);
        assertEquals("bucket-a", message.bucketName());
        assertEquals("admin/sess-1/pdf/file-1/report v1.pdf", message.objectKey());
        assertEquals("etag-001", message.objectEtag());
        assertNull(message.uploadMetadata());
        assertEquals("pdf", message.resolvedFileType());
        assertEquals("bucket-a|admin/sess-1/pdf/file-1/report v1.pdf|seq-001", message.deduplicationKey());
    }

    @Test
    void shouldParseSnsWrappedS3EventMessage() throws Exception {
        String rawMessage = "{\"Type\":\"Notification\",\"Message\":\"{\\\"Records\\\":[{\\\"eventName\\\":\\\"ObjectCreated:Put\\\",\\\"s3\\\":{\\\"bucket\\\":{\\\"name\\\":\\\"bucket-b\\\"},\\\"object\\\":{\\\"key\\\":\\\"admin/sess-2/txt/file-2/note.txt\\\",\\\"etag\\\":\\\"etag-002\\\"}}}]}\"}";

        List<S3UploadReceivedMessage> messages = parser.parse(rawMessage);

        assertEquals(1, messages.size());
        S3UploadReceivedMessage message = messages.get(0);
        assertEquals("bucket-b", message.bucketName());
        assertEquals("note.txt", message.fileName());
        assertEquals("txt", message.resolvedFileType());
        assertNull(message.uploadMetadata());
        assertNotNull(message.deduplicationKey());
    }

    @Test
    void shouldParseDirectUploadMetadataMessage() throws Exception {
        String rawMessage = """
                {
                  "fileId": "df05ba57-5488-4cbb-8c1e-de88e0d85527",
                  "userId": "admin",
                  "username": "admin",
                  "sessionId": "8ce0b093-1527-4e87-bc78-4cdead7b4a47",
                  "originalFilename": "关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf",
                  "s3Key": "admin/8ce0b093-1527-4e87-bc78-4cdead7b4a47/pdf/df05ba57-5488-4cbb-8c1e-de88e0d85527/关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf",
                  "bucket": "honghu-ai-document-upload",
                  "eTag": "etag-upload-001",
                  "sequencer": "seq-upload-001",
                  "fileType": "pdf",
                  "contentType": "application/pdf",
                  "fileSize": 265579,
                  "uploadedAt": "2026-05-03T18:41:42.338Z",
                  "versionGroupId": "vg_default",
                  "version": 1
                }
                """;

        List<S3UploadReceivedMessage> messages = parser.parse(rawMessage);

        assertEquals(1, messages.size());
        S3UploadReceivedMessage message = messages.get(0);
        assertEquals("honghu-ai-document-upload", message.bucketName());
        assertEquals("admin/8ce0b093-1527-4e87-bc78-4cdead7b4a47/pdf/df05ba57-5488-4cbb-8c1e-de88e0d85527/关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf", message.objectKey());
        assertEquals("ObjectCreated:UploadNotification", message.eventName());
        assertEquals(265579L, message.objectSize());
        assertEquals("pdf", message.resolvedFileType());
        assertEquals("df05ba57-5488-4cbb-8c1e-de88e0d85527", message.fileId());
        assertEquals("etag-upload-001", message.objectEtag());
        assertEquals("seq-upload-001", message.sequencer());
        assertNotNull(message.uploadMetadata());
        assertEquals("df05ba57-5488-4cbb-8c1e-de88e0d85527", message.uploadMetadata().fileId());
        assertEquals("admin", message.uploadMetadata().userId());
        assertEquals("admin", message.uploadMetadata().username());
        assertEquals("8ce0b093-1527-4e87-bc78-4cdead7b4a47", message.uploadMetadata().sessionId());
        assertEquals("关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf", message.uploadMetadata().originalFilename());
        assertEquals("admin/8ce0b093-1527-4e87-bc78-4cdead7b4a47/pdf/df05ba57-5488-4cbb-8c1e-de88e0d85527/关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf", message.uploadMetadata().s3Key());
        assertEquals("honghu-ai-document-upload", message.uploadMetadata().bucket());
        assertEquals("etag-upload-001", message.uploadMetadata().objectEtag());
        assertEquals("seq-upload-001", message.uploadMetadata().sequencer());
        assertEquals("pdf", message.uploadMetadata().fileType());
        assertEquals("application/pdf", message.uploadMetadata().contentType());
        assertEquals(265579L, message.uploadMetadata().fileSize());
        assertEquals("2026-05-03T18:41:42.338Z", message.uploadMetadata().uploadedAt());
        assertEquals("vg_default", message.uploadMetadata().versionGroupId());
        assertEquals(1, message.uploadMetadata().version());
        assertTrue(message.uploadMetadata().rawPayloadJson().contains("\"originalFilename\":\"关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf\""));
        assertEquals("honghu-ai-document-upload|admin/8ce0b093-1527-4e87-bc78-4cdead7b4a47/pdf/df05ba57-5488-4cbb-8c1e-de88e0d85527/关于因降薪被迫解除劳动合同方案及支付补偿的协商确认函.pdf|seq-upload-001", message.deduplicationKey());
    }

    @Test
    void shouldParseSnsWrappedUploadMetadataMessage() throws Exception {
        String rawMessage = "{\"Type\":\"Notification\",\"Message\":\"{\\\"fileId\\\":\\\"file-3\\\",\\\"s3Key\\\":\\\"admin/sess-3/txt/file-3/note+v2.txt\\\",\\\"bucket\\\":\\\"bucket-c\\\",\\\"etag\\\":\\\"etag-upload-002\\\",\\\"sequencer\\\":\\\"seq-upload-002\\\",\\\"fileSize\\\":\\\"42\\\",\\\"uploadedAt\\\":\\\"2026-05-03T18:41:42.338Z\\\",\\\"version\\\":2}\"}";

        List<S3UploadReceivedMessage> messages = parser.parse(rawMessage);

        assertEquals(1, messages.size());
        S3UploadReceivedMessage message = messages.get(0);
        assertEquals("bucket-c", message.bucketName());
        assertEquals("admin/sess-3/txt/file-3/note+v2.txt", message.objectKey());
        assertEquals("note+v2.txt", message.fileName());
        assertEquals("txt", message.resolvedFileType());
        assertEquals(42L, message.objectSize());
        assertEquals("ObjectCreated:UploadNotification", message.eventName());
        assertEquals("etag-upload-002", message.objectEtag());
        assertEquals("seq-upload-002", message.sequencer());
        assertNotNull(message.uploadMetadata());
        assertEquals("file-3", message.uploadMetadata().fileId());
        assertEquals("admin/sess-3/txt/file-3/note+v2.txt", message.uploadMetadata().s3Key());
        assertEquals("bucket-c", message.uploadMetadata().bucket());
        assertEquals("etag-upload-002", message.uploadMetadata().objectEtag());
        assertEquals("seq-upload-002", message.uploadMetadata().sequencer());
        assertEquals(42L, message.uploadMetadata().fileSize());
        assertEquals(2, message.uploadMetadata().version());
        assertEquals("bucket-c|admin/sess-3/txt/file-3/note+v2.txt|seq-upload-002", message.deduplicationKey());
    }

    @Test
    void shouldFallbackToSyntheticVersionWhenMetadataMessageHasNoEtagOrSequencer() throws Exception {
        String rawMessage = """
                {
                  "fileId": "file-legacy",
                  "s3Key": "admin/sess-legacy/txt/file-legacy/note.txt",
                  "bucket": "bucket-legacy",
                  "fileSize": 42,
                  "uploadedAt": "2026-05-03T18:41:42.338Z",
                  "version": 2
                }
                """;

        List<S3UploadReceivedMessage> messages = parser.parse(rawMessage);

        assertEquals(1, messages.size());
        S3UploadReceivedMessage message = messages.get(0);
        assertTrue(message.objectEtag().startsWith("metadata-version:fileId=file-legacy;version=2;uploadedAt=2026-05-03T18:41:42.338Z"));
        assertNull(message.sequencer());
        assertEquals("bucket-legacy|admin/sess-legacy/txt/file-legacy/note.txt|metadata|fileId=file-legacy;version=2;uploadedAt=2026-05-03T18:41:42.338Z", message.deduplicationKey());
    }
}

