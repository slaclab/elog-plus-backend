package edu.stanford.slac.elog_plus.v1.controller;

import com.github.javafaker.Faker;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.service.AttachmentService;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
import org.apache.kafka.clients.admin.AdminClient;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class AttachmentControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private AuthService authService;
    @Autowired
    private TestControllerHelperService testControllerHelperService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private DocumentGenerationService documentGenerationService;
    @Value("${edu.stanford.slac.elog-plus.image-preview-topic}")
    private String imagePreviewTopic;
    @Autowired
    private KafkaAdmin kafkaAdmin;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Entry.class);
        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            List<String> topicsToDelete = List.of(
                    imagePreviewTopic,
                    String.format("%s-retry-2000", imagePreviewTopic),
                    String.format("%s-retry-4000", imagePreviewTopic)
            );

            // Delete topics that actually exist
            topicsToDelete.stream()
                    .filter(existingTopics::contains)
                    .forEach(topic -> {
                        try {
                            adminClient.deleteTopics(Collections.singletonList(topic)).all().get();
                        } catch (Exception e) {
                            System.err.println("Failed to delete topic " + topic + ": " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to recreate Kafka topic", e);
        }
    }

    @Test
    public void createAttachment() throws Exception {
        ApiResultResponse<String> newAttachmentID = testControllerHelperService.newAttachment(
                mockMvc,
                status().isCreated(),
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                new MockMultipartFile(
                        "uploadFile",
                        "contract.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                )
        );

        Attachment retrivedAttachment = mongoTemplate.findOne(
                new Query().addCriteria(
                        Criteria.where("id").is(newAttachmentID.getPayload())
                ),
                Attachment.class
        );

        AssertionsForClassTypes.assertThat(retrivedAttachment).isNotNull();
    }

    @Test
    public void downloadAttachment() throws Exception {
        var newLogBookResult =  testControllerHelperService.getTestLogbook(mockMvc);

        Faker f = new Faker();
        String fileName = f.file().fileName();
        ApiResultResponse<String> newAttachmentID = testControllerHelperService.newAttachment(
                mockMvc,
                status().isCreated(),
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                new MockMultipartFile(
                        "uploadFile",
                        fileName,
                        MediaType.APPLICATION_PDF_VALUE,
                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertThat(newAttachmentID.getErrorCode()).isEqualTo(0);

        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .attachments(Set.of(newAttachmentID.getPayload()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        assertThat(newLogID.getErrorCode()).isEqualTo(0);

        testControllerHelperService.checkDownloadedFile(
                mockMvc,
                status().isOk(),
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                newAttachmentID.getPayload(),
                MediaType.APPLICATION_PDF_VALUE
        );
    }

    @Test
    public void downloadAttachmentAndPreview() throws Exception {
        var newLogBookResult =  testControllerHelperService.getTestLogbook(mockMvc);

        try (InputStream is = documentGenerationService.getTestPng()) {
            ApiResultResponse<String> newAttachmentID = testControllerHelperService.newAttachment(
                    mockMvc,
                    status().isCreated(),
                    Optional.of(
                            "user1@slac.stanford.edu"
                    ),
                    new MockMultipartFile(
                            "uploadFile",
                            "test.png",
                            MediaType.IMAGE_PNG_VALUE,
                            is
                    )
            );

            ApiResultResponse<String> newLogID =
                    assertDoesNotThrow(
                            () ->
                                    testControllerHelperService.createNewLog(
                                            mockMvc,
                                            status().isCreated(),
                                            Optional.of(
                                                    "user1@slac.stanford.edu"
                                            ),
                                            EntryNewDTO
                                                    .builder()
                                                    .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                    .attachments(Set.of(newAttachmentID.getPayload()))
                                                    .text("This is a log for test")
                                                    .title("A very wonderful log")
                                                    .build()
                                    )
                    );

            assertThat(newLogID.getErrorCode()).isEqualTo(0);

            testControllerHelperService.checkDownloadedFile(
                    mockMvc,
                    status().isOk(),
                    Optional.of(
                            "user1@slac.stanford.edu"
                    ),
                    newAttachmentID.getPayload(),
                    MediaType.IMAGE_PNG_VALUE
            );

            await().atMost(30, SECONDS).pollDelay(500, MILLISECONDS).until(
                    () -> {
                        String processingState = attachmentService.getPreviewProcessingState(newAttachmentID.getPayload());
                        return processingState.compareTo(
                                        Attachment.PreviewProcessingState.Completed.name()
                                )==0;
                    }
            );

            testControllerHelperService.checkDownloadedPreview(
                    mockMvc,
                    status().isOk(),
                    Optional.of(
                            "user1@slac.stanford.edu"
                    ),
                    newAttachmentID.getPayload(),
                    MediaType.IMAGE_JPEG_VALUE
            );
        }
    }
}
