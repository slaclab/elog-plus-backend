package edu.stanford.slac.elog_plus.task;


import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.NewLogbookDTO;
import edu.stanford.slac.elog_plus.config.ELOGAppProperties;
import edu.stanford.slac.elog_plus.exception.AttachmentNotFound;
import edu.stanford.slac.elog_plus.migration.M010_CreateIndexForAttachmentProcessing;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.repository.AttachmentRepository;
import edu.stanford.slac.elog_plus.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class TaskTest {
    @SpyBean
    private Clock clock; // Mock the Clock bean
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private CleanUnusedAttachment cleanUnusedAttachment;
    @Autowired
    private DocumentGenerationService documentGenerationService;
    @Autowired
    private ELOGAppProperties elogAppProperties;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private EntryService entryService;
    @Autowired
    private SharedUtilityService sharedUtilityService;
    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Logbook.class);

        // create index
        M010_CreateIndexForAttachmentProcessing createIndexForAttachmentProcessing = new M010_CreateIndexForAttachmentProcessing(mongoTemplate);
        assertDoesNotThrow(createIndexForAttachmentProcessing::changeSet);
        Mockito.reset(clock);
    }

    @Test
    public void removeAttachmentFromQueue() throws IOException {
        String attachmentId = null;
        try (InputStream is = assertDoesNotThrow(
                () -> documentGenerationService.getTestJpeg()
        )) {
            // create an attachment that will not be used
            attachmentId = assertDoesNotThrow(() -> attachmentService.createAttachment(
                            FileObjectDescription
                                    .builder()
                                    .fileName("jpegFileName")
                                    .contentType(MediaType.IMAGE_JPEG_VALUE)
                                    .is(is)
                                    .build(),
                            // we do not need to preview for this test
                            false
                    )
            );
        }

        // jmp to expiration date
        LocalDateTime now = LocalDateTime.now();
        when(clock.instant()).thenReturn(now.plusMinutes(elogAppProperties.getAttachmentExpirationMinutes()).atZone(ZoneId.systemDefault()).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        // run the task has it is supposed to do in future
        assertDoesNotThrow(() -> cleanUnusedAttachment.cleanExpiredNonUsedAttachments());

        // the attachment should dbe flagged as to delete
        String finalAttachmentId = attachmentId;
        var attachment = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachment.getCanBeDeleted()).isTrue();
    }

    @Test
    public void attachmentHasOptionalInfoClearedOnDelete() throws IOException {
        String attachmentId = null;
        try (InputStream is = assertDoesNotThrow(
                () -> documentGenerationService.getTestJpeg()
        )) {
            // create an attachment that will not be used
            attachmentId = assertDoesNotThrow(() -> attachmentService.createAttachment(
                            FileObjectDescription
                                    .builder()
                                    .fileName("jpegFileName")
                                    .contentType(MediaType.IMAGE_JPEG_VALUE)
                                    .is(is)
                                    .build(),
                            // we do not need to preview for this test
                            false,
                            Optional.of("test") // this attachment has optional info, that should expire as well
                    )
            );
        }
        // the attachment should dbe flagged as to delete
        String finalAttachmentId = attachmentId;
        var attachmentCreated = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachmentCreated.getReferenceInfo()).isNotNull();

        // jmp to expiration date
        LocalDateTime now = LocalDateTime.now();
        when(clock.instant()).thenReturn(now.plusMinutes(elogAppProperties.getAttachmentExpirationMinutes()).atZone(ZoneId.systemDefault()).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        // run the task has it is supposed to do in future
        assertDoesNotThrow(() -> cleanUnusedAttachment.cleanExpiredNonUsedAttachments());


        var attachment = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachment.getCanBeDeleted()).isTrue();
        assertThat(attachment.getReferenceInfo()).isNull();
    }

    @Test
    public void attachmentHasOptionalInfoClearedOnExpirationAndItIsInUse() throws IOException {
        String attachmentId = null;
        try (InputStream is = assertDoesNotThrow(
                () -> documentGenerationService.getTestJpeg()
        )) {
            // create an attachment that will not be used
            attachmentId = assertDoesNotThrow(() -> attachmentService.createAttachment(
                            FileObjectDescription
                                    .builder()
                                    .fileName("jpegFileName")
                                    .contentType(MediaType.IMAGE_JPEG_VALUE)
                                    .is(is)
                                    .build(),
                            // we do not need to preview for this test
                            false,
                            Optional.of("test") // this attachment has optional info, that should expire as well
                    )
            );
        }
        // the attachment should dbe flagged as to delete
        String finalAttachmentId = attachmentId;
        var attachmentCreated = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachmentCreated.getReferenceInfo()).isNotNull();
        assertThat(attachmentCreated.getCanBeDeleted()).isFalse();
        assertThat(attachmentCreated.getInUse()).isFalse();

        // associate entry to the attachment
        assertDoesNotThrow(() -> createEntryWithAttachment(finalAttachmentId));
        // checks
        attachmentCreated = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachmentCreated.getReferenceInfo()).isNotNull();
        assertThat(attachmentCreated.getCanBeDeleted()).isFalse();
        // now the in use is set byt the repetitive task
        assertThat(attachmentCreated.getInUse()).isFalse();

        // jmp to expiration date
        LocalDateTime now = LocalDateTime.now();
        when(clock.instant()).thenReturn(now.plusMinutes(elogAppProperties.getAttachmentExpirationMinutes()).atZone(ZoneId.systemDefault()).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        // run the task has it is supposed to do in future
        assertDoesNotThrow(() -> cleanUnusedAttachment.cleanExpiredNonUsedAttachments());


        var attachment = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachment.getCanBeDeleted()).isFalse();
        assertThat(attachment.getReferenceInfo()).isNull();
        assertThat(attachment.getInUse()).isTrue();
    }

    @Test
    public void entryWithAttachmentThatIsInDeleteStateFails() throws IOException {
        String attachmentId = null;
        try (InputStream is = assertDoesNotThrow(
                () -> documentGenerationService.getTestJpeg()
        )) {
            // create an attachment that will not be used
            attachmentId = assertDoesNotThrow(() -> attachmentService.createAttachment(
                            FileObjectDescription
                                    .builder()
                                    .fileName("jpegFileName")
                                    .contentType(MediaType.IMAGE_JPEG_VALUE)
                                    .is(is)
                                    .build(),
                            // we do not need to preview for this test
                            false,
                            Optional.of("test") // this attachment has optional info, that should expire as well
                    )
            );
        }
        // the attachment should dbe flagged as to delete
        String finalAttachmentId = attachmentId;
        var attachmentCreated = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachmentCreated.getReferenceInfo()).isNotNull();
        assertThat(attachmentCreated.getCanBeDeleted()).isFalse();
        assertThat(attachmentCreated.getInUse()).isFalse();

        // jmp to expiration date
        LocalDateTime now = LocalDateTime.now();
        when(clock.instant()).thenReturn(now.plusMinutes(elogAppProperties.getAttachmentExpirationMinutes()).atZone(ZoneId.systemDefault()).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.systemDefault());
        // run the task has it is supposed to do in future
        assertDoesNotThrow(() -> cleanUnusedAttachment.cleanExpiredNonUsedAttachments());

        var attachment = assertDoesNotThrow(()->attachmentRepository.findById(finalAttachmentId).orElseThrow(()->new RuntimeException("Attachment not found")));
        assertThat(attachment.getCanBeDeleted()).isTrue();
        assertThat(attachment.getReferenceInfo()).isNull();
        assertThat(attachment.getInUse()).isFalse();

        // associate entry to the attachment
        var attachmentNotFound = assertThrows(
                AttachmentNotFound.class,
                ()->createEntryWithAttachment(finalAttachmentId)
        );
        assertThat(attachmentNotFound).isNotNull();
    }


    /**
     * Create an entry with the attachment
     * @param finalAttachmentId the attachment id
     */
    private void createEntryWithAttachment(String finalAttachmentId) {
        String logbookId = logbookService.createNew(
                                NewLogbookDTO
                                        .builder()
                                        .name(UUID.randomUUID().toString())
                                        .build()
                        );
        assertThat(logbookId).isNotNull();

        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(Set.of(logbookId))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .attachments(Set.of(finalAttachmentId))
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();
    }
}
