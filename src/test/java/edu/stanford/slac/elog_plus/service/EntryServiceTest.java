package edu.stanford.slac.elog_plus.service;

import com.github.javafaker.Faker;
import com.icegreen.greenmail.junit.GreenMailRule;
import com.icegreen.greenmail.spring.GreenMailBean;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import edu.stanford.slac.ad.eed.baselib.config.SecurityAuditorAware;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.config.ELOGAppProperties;
import edu.stanford.slac.elog_plus.exception.EntryNotFound;
import edu.stanford.slac.elog_plus.exception.ShiftNotFound;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.repository.AttachmentRepository;
import edu.stanford.slac.elog_plus.task.CleanUnusedAttachment;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import jakarta.mail.Message;
import org.apache.kafka.clients.admin.AdminClient;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.Condition;
import org.jsoup.nodes.Element;
import org.junit.Rule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF;
import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF_ID;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.AssertionsForClassTypes.not;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EntryServiceTest {
    @Autowired
    private CleanUnusedAttachment cleanUnusedAttachment;
    @Autowired
    private EntryService entryService;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private SharedUtilityService sharedUtilityService;
    @Autowired
    MongoTemplate mongoTemplate;
    @Autowired
    private SecurityAuditorAware securityAuditorAware;
    @Value("${edu.stanford.slac.elog-plus.image-preview-topic}")
    private String imagePreviewTopic;
    @Autowired
    private KafkaAdmin kafkaAdmin;
    @Autowired
    private ELOGAppProperties elogAppProperties;
    @SpyBean
    private Clock clock; // Mock the Clock bean
    @Autowired
    private JavaMailSender mailSender;
    private GreenMail greenMail;

    @BeforeAll
    public void setup() {
        // Initialize GreenMail on default SMTP port (3025 for tests)
        greenMail = new GreenMail(ServerSetupTest.SMTP);
        greenMail.start();
    }
    @AfterEach
    public void tearDown() {
        greenMail.stop();
    }
    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        Mockito.reset(clock);
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

        // reset fake mail server
        greenMail.reset();
    }

    private LogbookDTO getTestLogbook() {
        String logbookId =
                assertDoesNotThrow(
                        () -> logbookService.createNew(
                                NewLogbookDTO
                                        .builder()
                                        .name(UUID.randomUUID().toString())
                                        .build()
                        )
                );
        return assertDoesNotThrow(
                () -> logbookService.getLogbook(logbookId)
        );
    }

    @Test
    public void testLogCreation() {
        var logbook = getTestLogbook();
        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(List.of(logbook.id()))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();
    }

    @Test
    public void testLogCreationRemoveDUplciatedLogbookId() {
        var logbook = getTestLogbook();
        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(List.of(logbook.id(), logbook.id()))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();

        // get full entry
        EntryDTO fullLog = assertDoesNotThrow(
                () -> entryService.getFullEntry(newLogID)
        );
        assertThat(fullLog).isNotNull();
        assertThat(fullLog.logbooks().size()).isEqualTo(1);
    }

    @Test
    public void testLogCreationWithEmailNotification() {
        var logbook = getTestLogbook();
        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(List.of(logbook.id()))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .userIdsToNotify(List.of("user2@slac.stanford.edu", "user3@slac.stanford.edu"))
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();
        // check that email has been sent
        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        Message[] messages = greenMail.getReceivedMessages();
        AssertionsForClassTypes.assertThat(messages.length).isEqualTo(2);
    }

    @Test
    public void testLogCreationWithUserWithMultipleName() {
        var logbook = getTestLogbook();
        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(List.of(logbook.id()))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user2@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();

        // get the full entry
        EntryDTO fullLog = assertDoesNotThrow(
                () -> entryService.getFullEntry(newLogID)
        );
        assertThat(fullLog).isNotNull();
        assertThat(fullLog.loggedBy()).isEqualTo("Name2 Name 2.1 Surname2");
    }

    @Test
    public void testFailBadAttachmentID() {
        var logbook = getTestLogbook();
        ControllerLogicException ex =
                assertThrows(
                        ControllerLogicException.class,
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .attachments(List.of("wrong id"))
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(ex.getErrorCode()).isEqualTo(-3);
    }

    @Test
    public void failGettingNotFoundLog() {
        EntryNotFound exception =
                assertThrows(
                        EntryNotFound.class,
                        () -> entryService.getFullEntry("wrong id")
                );
        assertThat(exception.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void testFetchFullLog() {
        var logbook = getTestLogbook();
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        EntryDTO fullLog =
                assertDoesNotThrow(
                        () -> entryService.getFullEntry(newLogID)
                );
        assertThat(fullLog.id()).isEqualTo(newLogID);
    }

    @Test
    public void testEventAtFetchFullLog() {
        var logbook = getTestLogbook();
        var eventAt = LocalDateTime.of(
                2023,
                7,
                1,
                0,
                1,
                0
        );
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .eventAt(
                                                eventAt
                                        )
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        EntryDTO fullLog =
                assertDoesNotThrow(
                        () -> entryService.getFullEntry(newLogID)
                );
        assertThat(fullLog.id()).isEqualTo(newLogID);
        assertThat(fullLog.eventAt()).isEqualTo(eventAt);
    }

    @Test
    public void testWithoutEventAtFetchFullLog() {
        var logbook = getTestLogbook();
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        EntryDTO fullLog =
                assertDoesNotThrow(
                        () -> entryService.getFullEntry(newLogID)
                );
        assertThat(fullLog.id()).isEqualTo(newLogID);
        assertThat(fullLog.eventAt()).isEqualTo(fullLog.loggedAt());
    }

    @Test
    public void testSupersedeCreationFailOnWrongRootLog() {
        var logbook = getTestLogbook();
        ControllerLogicException exception = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNewSupersede(
                        "bad id",
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .text("This is a log for test")
                                .title("A very wonderful log")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void testSupersedeOK() {
        var logbook = getTestLogbook();
        String supersededLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNewSupersede(
                                supersededLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for supersede")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        EntryDTO supersededLog = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        supersededLogID,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(true)
                )
        );

        assertThat(supersededLog).isNotNull();
        assertThat(supersededLog.supersededBy().id()).isEqualTo(newLogID);

        EntryDTO fullLog = assertDoesNotThrow(
                () -> entryService.getFullEntry(newLogID)
        );

        assertThat(fullLog).isNotNull();
        assertThat(fullLog.id()).isEqualTo(newLogID);
    }

    @Test
    public void testHistory() {
        var logbook = getTestLogbook();
        String supersededLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        String supersededLogIDTwo =
                assertDoesNotThrow(
                        () -> entryService.createNewSupersede(
                                supersededLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for supersede")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        String supersededLogIDNewest =
                assertDoesNotThrow(
                        () -> entryService.createNewSupersede(
                                supersededLogIDTwo,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for supersede of supersede")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        List<EntrySummaryDTO> history = new ArrayList<>();
        assertDoesNotThrow(
                () -> entryService.getLogHistory(supersededLogIDNewest, history)
        );
        assertThat(history).isNotEmpty();
        assertThat(history).extracting("id").containsExactly(supersededLogIDTwo, supersededLogID);
    }

    @Test
    public void testSupersedeErrorOnDoubleSuperseding() {
        var logbook = getTestLogbook();
        String supersededLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(supersededLogID).isNotNull();

        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNewSupersede(
                                supersededLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for supersede")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(newLogID).isNotNull();

        ControllerLogicException exception =
                assertThrows(
                        ControllerLogicException.class,
                        () -> entryService.createNewSupersede(
                                supersededLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for supersede one more time")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(exception.getErrorCode()).isEqualTo(-3);
    }

    @Test
    public void testFollowUpCreationFailOnWrongRootLog() {
        var logbook = getTestLogbook();
        ControllerLogicException exception = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNewFollowUp(
                        "bad root id",
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .text("This is a log for test")
                                .title("A very wonderful log")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void testFollowUpCreation() {
        var logbook = getTestLogbook();
        String rootLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(rootLogID).isNotNull();

        String newFollowUpOneID =
                assertDoesNotThrow(
                        () -> entryService.createNewFollowUp(
                                rootLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for followUps one")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(newFollowUpOneID).isNotNull();

        String newFollowUpTwoID =
                assertDoesNotThrow(
                        () -> entryService.createNewFollowUp(
                                rootLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for followUps two")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(newFollowUpTwoID).isNotNull();

        List<EntrySummaryDTO> followUpLogsFound =
                assertDoesNotThrow(
                        () -> entryService.getAllFollowUpForALog(
                                rootLogID
                        )
                );

        assertThat(followUpLogsFound).isNotNull();
        assertThat(followUpLogsFound.size()).isEqualTo(2);
        assertThat(followUpLogsFound.get(0).followingUp()).isEqualTo(rootLogID);
        assertThat(followUpLogsFound.get(1).followingUp()).isEqualTo(rootLogID);
    }

    @Test
    public void testFollowingUpIngFullLog() {
        var logbook = getTestLogbook();
        String rootLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(rootLogID).isNotNull();

        String newFollowUpOneID =
                assertDoesNotThrow(
                        () -> entryService.createNewFollowUp(
                                rootLogID,
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log updated for followUps one")
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(newFollowUpOneID).isNotNull();

        EntryDTO logWithFlowingUpLog =
                assertDoesNotThrow(
                        () -> entryService.getFullEntry(
                                newFollowUpOneID,
                                Optional.empty(),
                                Optional.of(true),
                                Optional.empty(),
                                Optional.of(true),
                                Optional.of(true),
                                Optional.empty()
                        )
                );

        assertThat(logWithFlowingUpLog).isNotNull();
        assertThat(logWithFlowingUpLog.followingUp()).isNotNull();
        assertThat(logWithFlowingUpLog.followingUp().id()).isEqualTo(rootLogID);
    }

    @Test
    public void testLogAttachmentOnFullDTO() {
        var logbook = getTestLogbook();
        Faker f = new Faker();
        String fileName = f.file().fileName(
                null,
                null,
                "pdf",
                null
        );
        String attachmentID =
                assertDoesNotThrow(
                        () -> attachmentService.createAttachment(
                                FileObjectDescription
                                        .builder()
                                        .fileName(fileName)
                                        .contentType(MediaType.APPLICATION_PDF_VALUE)
                                        .is(
                                                new ByteArrayInputStream(
                                                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                                                )
                                        )
                                        .build(),
                                false
                        )
                );
        assertThat(attachmentID).isNotNull();
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .attachments(List.of(attachmentID))
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );

        // check for the attachment are well filled into dto
        EntryDTO foundLog =
                assertDoesNotThrow(
                        () -> entryService.getFullEntry(newLogID)
                );
        assertThat(foundLog).isNotNull();
        assertThat(foundLog.attachments().size()).isEqualTo(1);
        assertThat(foundLog.attachments().get(0).fileName()).isEqualTo(fileName);
        assertThat(foundLog.attachments().get(0).contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
    }

    @Test
    public void testLogAttachmentExpiration() {
        var logbook = getTestLogbook();
        Faker f = new Faker();
        String fileName = f.file().fileName(
                null,
                null,
                "pdf",
                null
        );
        String attachmentIDThatWillExpires =
                assertDoesNotThrow(
                        () -> attachmentService.createAttachment(
                                FileObjectDescription
                                        .builder()
                                        .fileName(fileName)
                                        .contentType(MediaType.APPLICATION_PDF_VALUE)
                                        .is(
                                                new ByteArrayInputStream(
                                                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                                                )
                                        )
                                        .build(),
                                false,
                                Optional.of(AttachmentService.ATTACHMENT_QUEUED_REFERENCE)
                        )
                );
        assertThat(attachmentIDThatWillExpires).isNotNull();
        String attachmentID =
                assertDoesNotThrow(
                        () -> attachmentService.createAttachment(
                                FileObjectDescription
                                        .builder()
                                        .fileName(fileName)
                                        .contentType(MediaType.APPLICATION_PDF_VALUE)
                                        .is(
                                                new ByteArrayInputStream(
                                                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                                                )
                                        )
                                        .build(),
                                false,
                                Optional.of(AttachmentService.ATTACHMENT_QUEUED_REFERENCE)
                        )
                );
        assertThat(attachmentID).isNotNull();

        // create entry so the attachment id with attachmentID will be in use and will not be cancelled
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .attachments(List.of(attachmentID))
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        // call directly the method to clean the attachment
        // jmp to expiration date
        LocalDateTime now = LocalDateTime.now();
        when(clock.instant()).thenReturn(now.plusMinutes(elogAppProperties.getAttachmentExpirationMinutes()).atZone(ZoneId.systemDefault()).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        // run the task has it is supposed to do in future
        assertDoesNotThrow(() -> cleanUnusedAttachment.cleanExpiredNonUsedAttachments());

        var attachmentToBeRemoved = attachmentRepository.findById(attachmentIDThatWillExpires);
        assertThat(attachmentToBeRemoved.isPresent()).isTrue();
        assertThat(attachmentToBeRemoved.get().getCanBeDeleted()).isTrue();

        // the attachment with id attachmentID will exist
        assertThat(attachmentService.exists(attachmentID)).isTrue();
    }


    @Test
    public void testLogAttachmentOnSearchDTO() {
        var logbook = getTestLogbook();
        Faker f = new Faker();
        String fileName = f.file().fileName(
                null,
                null,
                "pdf",
                null
        );
        String attachmentID =
                assertDoesNotThrow(
                        () -> attachmentService.createAttachment(
                                FileObjectDescription
                                        .builder()
                                        .fileName(fileName)
                                        .contentType(MediaType.APPLICATION_PDF_VALUE)
                                        .is(
                                                new ByteArrayInputStream(
                                                        "<<pdf data>>".getBytes(StandardCharsets.UTF_8)
                                                )
                                        )
                                        .build(),
                                false
                        )
                );
        assertThat(attachmentID).isNotNull();
        String newLogID =
                assertDoesNotThrow(
                        () -> entryService.createNew(
                                EntryNewDTO
                                        .builder()
                                        .logbooks(List.of(logbook.id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .attachments(List.of(attachmentID))
                                        .build(),
                                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                        )
                );
        assertThat(newLogID).isNotNull();
        // check for the attachment are well filled into dto
        var foundLog =
                assertDoesNotThrow(
                        () -> entryService.findAll(
                                QueryWithAnchorDTO
                                        .builder()
                                        .limit(10)
                                        .logbooks(emptyList())
                                        .build()
                        )
                );
        assertThat(foundLog).isNotNull();
        assertThat(foundLog.size()).isEqualTo(1);
        assertThat(foundLog.get(0).attachments().size()).isEqualTo(1);
        assertThat(foundLog.get(0).attachments().get(0).fileName()).isEqualTo(fileName);
        assertThat(foundLog.get(0).attachments().get(0).contentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
    }

    @Test
    public void searchLogsByAnchor() {
        var logbook = getTestLogbook();

        // add tags to the logbook
        var tagId = assertDoesNotThrow(
                () -> logbookService.ensureTag(
                        logbook.id(),
                        "tag1"
                )
        );

        String anchorID = null;
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
            String newLogID =
                    assertDoesNotThrow(
                            () -> entryService.createNew(
                                    EntryNewDTO
                                            .builder()
                                            .logbooks(List.of(logbook.id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .tags(
                                                    List.of(
                                                            tagId
                                                    )
                                            )
                                            .build(),
                                    sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                            )
                    );
            assertThat(newLogID).isNotNull();
            if (idx == 49) {
                anchorID = newLogID;
            }
        }

        // initial search
        List<EntrySummaryDTO> firstPage = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .limit(10)
                                .logbooks(emptyList())
                                .build()
                )
        );
        assertThat(firstPage).isNotNull();
        assertThat(firstPage.size()).isEqualTo(10);
        String note = null;
        for (EntrySummaryDTO l :
                firstPage) {
            if (note == null) {
                note = l.note();
                continue;
            }
            assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }
        var testAnchorLog = firstPage.get(firstPage.size() - 1);
        // load next page
        List<EntrySummaryDTO> nextPage = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .anchorID(testAnchorLog.id())
                                .limit(10)
                                .logbooks(emptyList())
                                .build()
                )
        );

        assertThat(nextPage).isNotNull();
        assertThat(nextPage.size()).isEqualTo(10);
        // check that the first of next page is not the last of the previous
        assertThat(nextPage.get(0).id()).isNotEqualTo(firstPage.get(firstPage.size() - 1).id());

        note = nextPage.get(0).note();
        nextPage.remove(0);
        for (EntrySummaryDTO l :
                nextPage) {
            assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }

        // now get all the record upside and downside
        List<EntrySummaryDTO> prevPageByPin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .anchorID(testAnchorLog.id())
                                .contextSize(10)
                                .logbooks(emptyList())
                                .limit(0)
                                .build()
                )
        );
        assertThat(prevPageByPin).isNotNull();
        assertThat(prevPageByPin.size()).isEqualTo(10);
        assertThat(prevPageByPin).isEqualTo(firstPage);

        List<EntrySummaryDTO> prevAndNextPageByPin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .logbooks(emptyList())
                                .anchorID(testAnchorLog.id())
                                .contextSize(10)
                                .limit(10)
                                .build()
                )
        );

        assertThat(prevAndNextPageByPin).isNotNull();
        assertThat(prevAndNextPageByPin.size()).isEqualTo(20);
        assertThat(prevAndNextPageByPin).containsAll(firstPage);
        assertThat(prevAndNextPageByPin).containsAll(nextPage);


        // now search in the middle of the data set
        EntryDTO middleAnchorLog = entryService.getFullEntry(anchorID);
        List<EntrySummaryDTO> prevAndNextPageByMiddlePin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .logbooks(emptyList())
                                .anchorID(middleAnchorLog.id())
                                .contextSize(10)
                                .limit(10)
                                .build()
                )
        );
        assertThat(prevAndNextPageByMiddlePin).isNotNull();
        assertThat(prevAndNextPageByMiddlePin.size()).isEqualTo(20);
        assertThat(prevAndNextPageByMiddlePin.get(0).note()).isEqualTo("58");
        assertThat(prevAndNextPageByMiddlePin.get(19).note()).isEqualTo("39");
    }

    @Test
    public void searchLogsByAnchorReverseEventAtAndOrderedByLogged() {
        LocalDateTime now = LocalDateTime.now();
        var logbook = getTestLogbook();
        String anchorID = null;
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
            String newLogID =
                    assertDoesNotThrow(
                            () -> entryService.createNew(
                                    EntryNewDTO
                                            .builder()
                                            .logbooks(List.of(logbook.id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .eventAt(
                                                    now.minusDays(finalIdx)
                                            )
                                            .build(),
                                    sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                            )
                    );
            assertThat(newLogID).isNotNull();
            if (idx == 49) {
                anchorID = newLogID;
            }
        }

        // initial search
        List<EntrySummaryDTO> firstPage = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .limit(10)
                                .logbooks(emptyList())
                                .sortByLogDate(true)
                                .build()
                )
        );
        assertThat(firstPage).isNotNull();
        assertThat(firstPage.size()).isEqualTo(10);
        String note = null;
        for (EntrySummaryDTO l :
                firstPage) {
            if (note == null) {
                note = l.note();
                continue;
            }
            assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }
        var testAnchorLog = firstPage.get(firstPage.size() - 1);
        // load next page
        List<EntrySummaryDTO> nextPage = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .anchorID(testAnchorLog.id())
                                .limit(10)
                                .logbooks(emptyList())
                                .sortByLogDate(true)
                                .build()
                )
        );

        assertThat(nextPage).isNotNull();
        assertThat(nextPage.size()).isEqualTo(10);
        // check that the first of next page is not the last of the previous
        assertThat(nextPage.get(0).id()).isNotEqualTo(firstPage.get(firstPage.size() - 1).id());

        note = nextPage.get(0).note();
        nextPage.remove(0);
        for (EntrySummaryDTO l :
                nextPage) {
            assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }

        // now get all the record upside and downside
        List<EntrySummaryDTO> prevPageByPin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .logbooks(emptyList())
                                .anchorID(testAnchorLog.id())
                                .contextSize(10)
                                .limit(0)
                                .sortByLogDate(true)
                                .build()
                )
        );
        assertThat(prevPageByPin).isNotNull();
        assertThat(prevPageByPin.size()).isEqualTo(10);
        assertThat(prevPageByPin).isEqualTo(firstPage);

        List<EntrySummaryDTO> prevAndNextPageByPin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .logbooks(emptyList())
                                .anchorID(testAnchorLog.id())
                                .contextSize(10)
                                .limit(10)
                                .sortByLogDate(true)
                                .build()
                )
        );

        assertThat(prevAndNextPageByPin).isNotNull();
        assertThat(prevAndNextPageByPin.size()).isEqualTo(20);
        assertThat(prevAndNextPageByPin).containsAll(firstPage);
        assertThat(prevAndNextPageByPin).containsAll(nextPage);


        // now search in the middle of the data set
        EntryDTO middleAnchorLog = entryService.getFullEntry(anchorID);
        List<EntrySummaryDTO> prevAndNextPageByMiddlePin = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .logbooks(emptyList())
                                .anchorID(middleAnchorLog.id())
                                .contextSize(10)
                                .limit(10)
                                .sortByLogDate(true)
                                .build()
                )
        );
        assertThat(prevAndNextPageByMiddlePin).isNotNull();
        assertThat(prevAndNextPageByMiddlePin.size()).isEqualTo(20);
        assertThat(prevAndNextPageByMiddlePin.get(0).note()).isEqualTo("58");
        assertThat(prevAndNextPageByMiddlePin.get(19).note()).isEqualTo("39");
    }

    @Test
    public void searchLogResultShowCorrectShift() {
        var logbook = getTestLogbook();

        //add shifts
        logbookService.replaceShift(
                logbook.id(),
                List.of(
                        ShiftDTO
                                .builder()
                                .name("Shift1")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        0,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        7,
                                                        59
                                                )
                                        )
                                )
                                .build(),
                        ShiftDTO
                                .builder()
                                .name("Shift2")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        8,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        12,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );

        String anchorID = null;
        // create some data
        Random random = new Random();
        for (int idx = 0; idx < 30; idx++) {
            String newLogID =
                    assertDoesNotThrow(
                            () -> entryService.createNew(
                                    EntryNewDTO
                                            .builder()
                                            .logbooks(List.of(logbook.id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .eventAt(
                                                    LocalDateTime.now().withHour(
                                                                    random.nextInt(24)
                                                            )
                                                            .withMinute(
                                                                    random.nextInt(60)
                                                            )
                                            )
                                            .build(),
                                    sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                            )
                    );
            assertThat(newLogID).isNotNull();
        }

        // initial search
        List<EntrySummaryDTO> firstPage = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .limit(30)
                                .logbooks(emptyList())
                                .build()
                )
        );
        assertThat(firstPage).isNotNull();
        assertThat(firstPage.size()).isEqualTo(30);

        Condition<EntrySummaryDTO> outOfShift = new Condition<>(
                e -> e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                13,
                                0
                        )
                ) || e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                23,
                                59
                        )
                ) || e.eventAt().toLocalTime().isAfter(
                        LocalTime.of(
                                13,
                                0
                        )
                ) || e.eventAt().toLocalTime().isBefore(
                        LocalTime.of(
                                23,
                                59
                        )
                )
                ,
                "no shift");
        Condition<EntrySummaryDTO> shift1 = new Condition<>(
                e -> e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                0,
                                0
                        )
                ) || e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                7,
                                59
                        )
                ) || e.eventAt().toLocalTime().isAfter(
                        LocalTime.of(
                                0,
                                0
                        )
                ) || e.eventAt().toLocalTime().isBefore(
                        LocalTime.of(
                                7,
                                59
                        )
                )
                ,
                "Shift1");
        Condition<EntrySummaryDTO> shift2 = new Condition<>(
                e -> e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                8,
                                0
                        )
                ) || e.eventAt().toLocalTime().equals(
                        LocalTime.of(
                                12,
                                59
                        )
                ) || e.eventAt().toLocalTime().isAfter(
                        LocalTime.of(
                                8,
                                0
                        )
                ) || e.eventAt().toLocalTime().isBefore(
                        LocalTime.of(
                                12,
                                59
                        )
                )
                ,
                "Shift2");

        for (EntrySummaryDTO entry :
                firstPage) {
            if (entry.shifts() == null || entry.shifts().isEmpty()) continue;
            assertThat(entry.shifts().get(0).logbook().id()).isEqualTo(logbook.id());
        }

        //check all shift
        assertThat(firstPage)
                .filteredOn(entry -> entry.shifts() == null || entry.shifts().isEmpty())
                .filteredOn(not(outOfShift))
                .hasSize(0);
        assertThat(firstPage)
                // select only shift 1
                .filteredOn(entry -> entry.shifts() != null || entry.shifts().get(0).name().compareTo("Shift1") == 0)
                // remove shift 1
                .filteredOn(not(shift1))
                .hasSize(0);
        assertThat(firstPage)
                // select only shift 1
                .filteredOn(entry -> entry.shifts() != null || entry.shifts().get(0).name().compareTo("Shift2") == 0)
                // remove shift 1
                .filteredOn(not(shift2))
                .hasSize(0);
        // check summary against full entry
        for (EntrySummaryDTO es :
                firstPage) {
            EntryDTO fullEntry = entryService.getFullEntry(
                    es.id()
            );
            assertThat(fullEntry).isNotNull();
            assertThat(fullEntry.shifts()).isEqualTo(es.shifts());
        }
    }

    @Test
    public void testSummarizationFailWrongShiftAndDate() {
        var logbookTest = getTestLogbook();

        //try to save a summary without any shift on logbooks
        ControllerLogicException exceptionOnNoShiftOnLogbook = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exceptionOnNoShiftOnLogbook.getErrorCode()).isEqualTo(-1);

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .name("Shift1")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    0,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    7,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build()
                            )
                    );
                    return null;
                }
        );

        //try to save a summary
        ControllerLogicException exceptionOnNoShiftName = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exceptionOnNoShiftName.getErrorCode()).isEqualTo(-2);

        ControllerLogicException exceptionOnNoDate = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId("wrong id")
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exceptionOnNoDate.getErrorCode()).isEqualTo(-3);

        ShiftNotFound exceptionOnNotFoundShiftName = assertThrows(
                ShiftNotFound.class,
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId("wrong id")
                                                .date(LocalDate.now())
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(exceptionOnNotFoundShiftName.getErrorCode()).isEqualTo(-4);
    }

    @Test
    public void testSummarization() {
        var logbookTest = getTestLogbook();
        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .name("Shift1")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    0,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    7,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift2")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    8,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    12,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift3")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    13,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    17,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build()
                            )
                    );
                    return null;
                }
        );

        LogbookDTO lb = assertDoesNotThrow(
                () -> logbookService.getLogbookByName(
                        logbookTest.name()
                )
        );

        //try to save a summary
        String entryID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId(lb.shifts().get(0).id())
                                                .date(LocalDate.now())
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(entryID).isNotNull().isNotEmpty();
    }

    @Test
    public void testSearchFilteringOnSummaries() {
        var logbookTest = getTestLogbook();
        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .name("Shift1")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    0,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    7,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift2")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    8,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    12,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift3")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    13,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    17,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build()
                            )
                    );
                    return null;
                }
        );
        LogbookDTO lb = assertDoesNotThrow(
                () -> logbookService.getLogbookByName(
                        logbookTest.name()
                )
        );

        //try to save a summary
        String entryID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title summary")
                                .text("text summary")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId(lb.shifts().get(0).id())
                                                .date(LocalDate.now())
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID).isNotNull().isNotEmpty();

        entryID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title")
                                .text("text")
                                .logbooks(List.of(logbookTest.id()))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID).isNotNull().isNotEmpty();

        List<EntrySummaryDTO> found = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .limit(10)
                                .logbooks(emptyList())
                                .build()
                )
        );
        assertThat(found).isNotNull().hasSize(2);

        found = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .hideSummaries(true)
                                .limit(10)
                                .logbooks(emptyList())
                                .build()
                )
        );
        assertThat(found).isNotNull().hasSize(1);
    }

    @Test
    public void findSummaryId() {
        var logbookTest = getTestLogbook();
        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .name("Shift1")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    0,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    7,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift2")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    8,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    12,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift3")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    13,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    17,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build()
                            )
                    );
                    return null;
                }
        );
        LogbookDTO lb = assertDoesNotThrow(
                () -> logbookService.getLogbookByName(
                        logbookTest.name()
                )
        );
        //try to save a summary
        String entryID1 = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title summary")
                                .text("text summary")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId(lb.shifts().get(0).id())
                                                .date(LocalDate.now())
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID1).isNotNull().isNotEmpty();
        String entryID2 = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title summary")
                                .text("text summary")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId(lb.shifts().get(0).id())
                                                .date(LocalDate.now().minusDays(1))
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID2).isNotNull().isNotEmpty();

        String idFound1 = assertDoesNotThrow(
                () -> entryService.findSummaryIdForShiftIdAndDate(
                        lb.shifts().get(0).id(),
                        LocalDate.now()
                )
        );
        assertThat(idFound1).isNotNull().isNotEmpty().isEqualTo(entryID1);

        String idFound2 = assertDoesNotThrow(
                () -> entryService.findSummaryIdForShiftIdAndDate(
                        lb.shifts().get(0).id(),
                        LocalDate.now().minusDays(1)
                )
        );
        assertThat(idFound2).isNotNull().isNotEmpty().isEqualTo(entryID2);
    }

    @Test
    public void failingDeletingShiftAssociatedToASummary() {
        var logbookTest = getTestLogbook();
        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .name("Shift1")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    0,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    7,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift2")
                                            .from(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    8,
                                                                    0
                                                            )
                                                    )
                                            )
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    12,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build(),
                                    ShiftDTO
                                            .builder()
                                            .name("Shift3")
                                            .from(DateUtilities.toUTCString(
                                                    LocalTime.of(
                                                            13,
                                                            0
                                                    )
                                            ))
                                            .to(
                                                    DateUtilities.toUTCString(
                                                            LocalTime.of(
                                                                    17,
                                                                    59
                                                            )
                                                    )
                                            )
                                            .build()
                            )
                    );
                    return null;
                }
        );
        LogbookDTO lb = assertDoesNotThrow(
                () -> logbookService.getLogbookByName(
                        logbookTest.name()
                )
        );
        assertThat(lb.shifts()).isNotNull().hasSize(3);
        //try to save a summary
        String entryID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title summary")
                                .text("text summary")
                                .logbooks(List.of(logbookTest.id()))
                                .summarizes(
                                        SummarizesDTO
                                                .builder()
                                                .shiftId(lb.shifts().get(1).id())
                                                .date(LocalDate.now())
                                                .build()
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID).isNotNull().isNotEmpty();

        //try to delete the shift used by the summary
        ControllerLogicException deleteException = assertThrows(
                ControllerLogicException.class,
                () -> {
                    logbookService.replaceShift(
                            logbookTest.id(),
                            List.of(
                                    ShiftDTO
                                            .builder()
                                            .id(lb.shifts().get(2).id())
                                            .name("Shift3Modified")
                                            .from("13:00")
                                            .to("17:59")
                                            .build()
                            )
                    );
                }
        );

        assertThat(deleteException.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void failingDeletingTagsAssociatedToASummary() {
        var logbookTest = getTestLogbook();
        LogbookDTO logbookTestUpdated = assertDoesNotThrow(
                () -> logbookService.update(
                        logbookTest.id(),
                        UpdateLogbookDTO
                                .builder()
                                .name(logbookTest.name())
                                .shifts(
                                        emptyList()
                                )
                                .tags(
                                        List.of(
                                                TagDTO
                                                        .builder()
                                                        .name("tag1")
                                                        .build()
                                        )
                                )
                                .build()
                )
        );
        assertThat(logbookTestUpdated).isNotNull();
        //try to save a summary
        String entryID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .title("title summary")
                                .text("text summary")
                                .logbooks(List.of(logbookTest.id()))
                                .tags(
                                        List.of(
                                                logbookTestUpdated.tags().get(0).id()
                                        )
                                )
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(entryID).isNotNull().isNotEmpty();

        //try to delete the shift used by the summary
        ControllerLogicException failForDeleteAssignedTag = assertThrows(
                ControllerLogicException.class,
                () -> {
                    logbookService.update(
                            logbookTest.id(),
                            UpdateLogbookDTO
                                    .builder()
                                    .name(logbookTest.name())
                                    .shifts(
                                            emptyList()
                                    )
                                    .tags(
                                            List.of(
                                                    TagDTO
                                                            .builder()
                                                            .name("tag2")
                                                            .build()
                                            )
                                    )
                                    .build()
                    );
                }
        );

        assertThat(failForDeleteAssignedTag.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void testReferencesOk() {
        var logbook = getTestLogbook();
        String referencedEntryId = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("Referenced entry")
                                .text("This is a log for a referenced entry")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(referencedEntryId).isNotNull();

        Element fragment = new Element("div");
        fragment.appendText("This is a text with reference");
        fragment.appendElement(ELOG_ENTRY_REF).attr(ELOG_ENTRY_REF_ID, referencedEntryId);

        String referencerEntryId = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("New entry")
                                .text(sharedUtilityService.createReferenceHtmlFragment("text for the reference entry", List.of(referencedEntryId)))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(referencerEntryId).isNotNull();

        //fetch referencer
        EntryDTO referencerEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referencerEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true)

                )
        );
        assertThat(referencerEntry.references()).hasSize(1).extracting("id").contains(referencedEntryId);
        assertThat(sharedUtilityService.htmlContainsReferenceWithId(referencerEntry.text(), referencedEntryId)).isTrue();
        //fetch referenced
        EntryDTO referencedEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referencedEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),
                        Optional.of(true)
                )
        );

        assertThat(referencedEntry.referencedBy()).hasSize(1).extracting("id").contains(referencerEntryId);
    }

    @Test
    public void testReferencesFailsOnBadReferenceId() {
        var logbook = getTestLogbook();

        String newEntryId = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("New entry")
                                .text("This is a text with reference in a link <a href=\"http://test.com/entry/%s\">Reference link</a>".formatted("bad-id"))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(newEntryId).isNotNull();

        EntryDTO newEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        newEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true)
                )
        );

        assertThat(newEntry.references()).hasSize(0);
    }

    @Test
    public void testReferencesOnFindHidingSupersededOneOk() {
        var logbook = getTestLogbook();
        String referencedEntryId = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("Referenced entry")
                                .text("This is a log for a referenced entry")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(referencedEntryId).isNotNull();

        String referencerEntryId = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("New entry")
                                .text(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", singletonList(referencedEntryId)))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(referencerEntryId).isNotNull();


        // now supersede the entry that reference the first one

        String referencerSupersedeEntryId = assertDoesNotThrow(
                () -> entryService.createNewSupersede(
                        referencerEntryId,
                        EntryNewDTO
                                .builder()
                                .logbooks(List.of(logbook.id()))
                                .title("New entry that supersede the referencer one")
                                .text(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", List.of(referencedEntryId, referencedEntryId)))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(referencerSupersedeEntryId).isNotNull();

        //find entry
        EntryDTO referencedEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referencedEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true)
                )
        );

        EntryDTO referencerEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referencerSupersedeEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true)
                )
        );

        assertThat(referencerEntry.references()).hasSize(1).extracting("id").contains(referencedEntryId);
        assertThat(referencedEntry.referencedBy()).hasSize(1).extracting("id").contains(referencerSupersedeEntryId);
    }
}
