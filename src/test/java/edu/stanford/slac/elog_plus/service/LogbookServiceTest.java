package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.NewAuthenticationTokenDTO;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.exception.*;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Math.abs;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class LogbookServiceTest {
    @Autowired
    private SharedUtilityService sharedUtilityService;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), AuthenticationToken.class);
    }

    @Test
    public void createNew() {
        String newID = sharedUtilityService.getTestLogbook();
        assertThat(newID).isNotNull().isNotEmpty();
        var fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(newID)
        );

        AssertionsForClassTypes.assertThat(fullLogbook.readAll()).isFalse();
        AssertionsForClassTypes.assertThat(fullLogbook.writeAll()).isFalse();
    }

    @Test
    public void createNewCheckReadWriteAllFlag() {
        String newLogbookID = assertDoesNotThrow(
                () -> logbookService.createNew(
                        NewLogbookDTO
                                .builder()
                                .name("logbookName")
                                .readAll(true)
                                .writeAll(true)
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newLogbookID).isNotNull().isNotEmpty();

        var fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(newLogbookID)
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.readAll()).isTrue();
        assertThat(fullLogbook.writeAll()).isTrue();
    }

    @Test
    public void fetchAll() {
        String newID = sharedUtilityService.getTestLogbook();

        assertThat(newID).isNotNull().isNotEmpty();

        List<LogbookDTO> allLogbook = assertDoesNotThrow(
                () -> logbookService.getAllLogbook()
        );

        assertThat(allLogbook).isNotNull().isNotEmpty();
    }

    @Test
    public void createTag() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        AssertionsForClassTypes.assertThat(newLogbookID).isNotNull().isNotEmpty();

        String newTagID = assertDoesNotThrow(
                () -> logbookService.createNewTag(
                        newLogbookID,
                        NewTagDTO
                                .builder()
                                .name("new-tag")
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newTagID).isNotNull().isNotEmpty();

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(newLogbookID)
        );
        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.tags()).isNotEmpty();

        List<TagDTO> allTags = assertDoesNotThrow(
                () -> logbookService.getAllTags(newLogbookID)
        );
        assertThat(allTags).isNotNull();
        assertThat(allTags).isNotEmpty();

        assertThat(fullLogbook.tags()).usingRecursiveFieldByFieldElementComparatorIgnoringFields("logbook").containsAll(allTags);
    }


    @Test
    public void ensureTag() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        AssertionsForClassTypes.assertThat(newLogbookID).isNotNull().isNotEmpty();
        Set<String> returnedTagID = new HashSet<>();
        Integer counter = 0;
        Set<String> tagsNameToInsert = new HashSet<String>() {{
            add("new_tag_1");
            add("new_tag_2");
            add("new_tag_3");
        }};
        // now test with multithreading
        int numberOfThreads = 10;
        int numberOfExecution = 20;
        ExecutorService service = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        for (int i = 0; i < numberOfExecution; i++) {
            service.execute(() -> {
                Random rand = new Random();
                String tagName = null;
                synchronized (tagsNameToInsert) {
                    tagName = tagsNameToInsert.toArray(new String[tagsNameToInsert.size()])[abs(rand.nextInt()) % 3];
                }
                String finalTagName = tagName;
                var newId = assertDoesNotThrow(
                        () -> logbookService.ensureTag(
                                newLogbookID,
                                finalTagName
                        )
                );
                synchronized (returnedTagID) {
                    returnedTagID.add(
                            newId
                    );
                }
                latch.countDown();
            });
        }
        assertDoesNotThrow(
                () -> latch.await()
        );
        var allTags = assertDoesNotThrow(
                () -> logbookService.getAllTags(newLogbookID)
        );
        assertThat(returnedTagID).hasSize(3);
        assertThat(allTags)
                .hasSize(3)
                .extracting("id")
                .containsAll(returnedTagID);

    }

    @Test
    public void failAddingShiftWithBadTimeFrom() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        ControllerLogicException exceptBadTime = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from("35:73")
                                .to("48:81")
                                .build()
                )
        );
        assertThat(exceptBadTime.getErrorCode()).isEqualTo(-1);
        assertThat(exceptBadTime.getErrorMessage()).containsPattern(".*'from'.*range 00:01-23:59");

        exceptBadTime = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from("00:73")
                                .to("48:81")
                                .build()
                )
        );
        assertThat(exceptBadTime.getErrorCode()).isEqualTo(-1);
        assertThat(exceptBadTime.getErrorMessage()).containsPattern(".*'from'.*range 00:01-23:59");

        exceptBadTime = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from("00:30")
                                .to("48:81")
                                .build()
                )
        );
        assertThat(exceptBadTime.getErrorCode()).isEqualTo(-1);
        assertThat(exceptBadTime.getErrorMessage()).containsPattern(".*'to'.*range 00:01-23:59");

        exceptBadTime = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from("00:30")
                                .to("00:81")
                                .build()
                )
        );
        assertThat(exceptBadTime.getErrorCode()).isEqualTo(-1);
        assertThat(exceptBadTime.getErrorMessage()).containsPattern(".*'to'.*range 00:01-23:59");
    }

    @Test
    public void shiftAddFailNoLogbook() {
        ControllerLogicException exceptNoLogbook = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        "wrong id",
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from("00:30")
                                .to("00:50")
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(exceptNoLogbook.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void shiftAddFailWrongDates() {
        String newLogbookID = sharedUtilityService.getTestLogbook();

        ControllerLogicException exceptNoLogbook = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.replaceShift(
                        newLogbookID,
                        List.of(
                                ShiftDTO
                                        .builder()
                                        .name("Shift1")
                                        .from(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                7,
                                                                0
                                                        )
                                                )
                                        )
                                        .to(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                11,
                                                                59
                                                        )
                                                ))
                                        .build(),
                                ShiftDTO
                                        .builder()
                                        .name("Shift2")
                                        .from(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                12,
                                                                0
                                                        )
                                                )
                                        )
                                        .to(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                13,
                                                                0
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
                                                                8,
                                                                0
                                                        )
                                                )
                                        )
                                        .to(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                6,
                                                                59
                                                        )
                                                )
                                        )
                                        .build()
                        )
                )
        );
        AssertionsForClassTypes.assertThat(exceptNoLogbook.getErrorCode()).isEqualTo(-3);
    }

    @Test
    public void shiftAddOk() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        String shiftId = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from(DateUtilities.toUTCString(
                                        LocalTime.of(
                                                0,
                                                1
                                        )
                                ))
                                .to(DateUtilities.toUTCString(
                                        LocalTime.of(
                                                3,
                                                59
                                        )
                                ))
                                .build()
                )
        );
        assertThat(shiftId).isNotNull().isNotEmpty();

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("id").contains(shiftId);
    }

    @Test
    public void shiftAddFailOnOverlapping() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        String shiftId1 = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift1")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        0,
                                                        0
                                                )
                                        ))
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        0,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );
        assertThat(shiftId1).isNotNull().isNotEmpty();

        String shiftId2 = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift2")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );
        assertThat(shiftId2).isNotNull().isNotEmpty();

        // fails on various overlapping rules

        ControllerLogicException exceptOverlap = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("ShiftFails")
                                .from(DateUtilities.toUTCString(
                                        LocalTime.of(
                                                0,
                                                30
                                        )
                                ))
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        20
                                                )
                                        )
                                )
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(exceptOverlap.getErrorCode()).isEqualTo(-3);
        AssertionsForClassTypes.assertThat(exceptOverlap.getErrorMessage()).containsPattern(".*ShiftFails.*overlap.*Shift1");

        exceptOverlap = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("ShiftFails")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        1,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        20
                                                )
                                        )
                                )
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(exceptOverlap.getErrorCode()).isEqualTo(-3);
        AssertionsForClassTypes.assertThat(exceptOverlap.getErrorMessage()).containsPattern(".*ShiftFails.*overlap.*Shift2");
    }

    @Test
    public void shiftAddOkInTheMiddle() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        String shiftId1 = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
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
                                                        0,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );
        assertThat(shiftId1).isNotNull().isNotEmpty();

        String shiftId2 = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift2")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        2,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );
        assertThat(shiftId2).isNotNull().isNotEmpty();

        String shiftId3 = assertDoesNotThrow(
                () -> logbookService.addShift(
                        newLogbookID,
                        NewShiftDTO
                                .builder()
                                .name("Shift3")
                                .from(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        1,
                                                        0
                                                )
                                        )
                                )
                                .to(
                                        DateUtilities.toUTCString(
                                                LocalTime.of(
                                                        1,
                                                        59
                                                )
                                        )
                                )
                                .build()
                )
        );
        assertThat(shiftId3).isNotNull().isNotEmpty();


        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("id").contains(shiftId1, shiftId2, shiftId3);
    }

    @Test
    public void shiftReplaceOKWithEmptyLogbook() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("name").contains("Shift1", "Shift2", "Shift3");
    }


    @Test
    public void testShiftMattBug() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        LogbookDTO createdLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );
        assertThat(createdLogbook).isNotNull();
        assertThat(createdLogbook.id()).isEqualTo(newLogbookID);
        // update logbook with owl shift (12::00AM-07:59AM) and Day Shift (08:00AM-03:59PM)
        LogbookDTO updatedLogbook = assertDoesNotThrow(
                () -> logbookService.update(
                        newLogbookID,
                        UpdateLogbookDTO
                                .builder()
                                .name(createdLogbook.name())
                                .tags(emptyList())
                                .shifts(
                                        List.of(
                                                ShiftDTO
                                                        .builder()
                                                        .name("Owl Shift")
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
                                                        .name("Day Shift")
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
                                                                                15,
                                                                                59
                                                                        )
                                                                )
                                                        )
                                                        .build()
                                        )
                                )
                                .build()
                )
        );
        assertThat(updatedLogbook).isNotNull();
        assertThat(updatedLogbook.id()).isEqualTo(newLogbookID);
        assertThat(updatedLogbook.shifts()).extracting("name").contains("Owl Shift", "Day Shift");
        assertThat(updatedLogbook.shifts()).extracting("from").contains(
                DateUtilities.toUTCString(
                        LocalTime.of(
                                0,
                                0
                        )
                ),
                DateUtilities.toUTCString(
                        LocalTime.of(
                                8,
                                0
                        )
                )
        );

        // add now the swing shift (04:00PM-11:59PM)
        LogbookDTO anotherLogbookUpdate = assertDoesNotThrow(
                () -> logbookService.update(
                        newLogbookID,
                        UpdateLogbookDTO
                                .builder()
                                .name(updatedLogbook.name())
                                .tags(emptyList())
                                .shifts(
                                        List.of(
                                                ShiftDTO
                                                        .builder()
                                                        .name("Owl Shift")
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
                                                        .name("Day Shift")
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
                                                                                15,
                                                                                59
                                                                        )
                                                                )
                                                        )
                                                        .build(),
                                                ShiftDTO
                                                        .builder()
                                                        .name("Swing Shift")
                                                        .from(
                                                                DateUtilities.toUTCString(
                                                                        LocalTime.of(
                                                                                16,
                                                                                0
                                                                        )
                                                                )
                                                        )
                                                        .to(
                                                                DateUtilities.toUTCString(
                                                                        LocalTime.of(
                                                                                23,
                                                                                59
                                                                        )
                                                                )
                                                        )
                                                        .build()
                                        )
                                )
                                .build()
                )
        );
        assertThat(anotherLogbookUpdate).isNotNull();
        assertThat(anotherLogbookUpdate.id()).isEqualTo(newLogbookID);
        assertThat(anotherLogbookUpdate.shifts()).extracting("name").contains("Owl Shift", "Day Shift", "Swing Shift");
        assertThat(anotherLogbookUpdate.shifts()).extracting("from").contains(
                DateUtilities.toUTCString(
                        LocalTime.of(
                                0,
                                0
                        )
                ),
                DateUtilities.toUTCString(
                        LocalTime.of(
                                8,
                                0
                        )
                ),
                DateUtilities.toUTCString(
                        LocalTime.of(
                                16,
                                0
                        )
                )
        );
        assertThat(anotherLogbookUpdate.shifts()).extracting("to").contains(
                DateUtilities.toUTCString(
                        LocalTime.of(
                                7,
                                59
                        )
                ),
                DateUtilities.toUTCString(
                        LocalTime.of(
                                15,
                                59
                        )
                ),
                DateUtilities.toUTCString(
                        LocalTime.of(
                                23,
                                59
                        )
                )
        );
    }

    @Test
    public void shiftReplaceFailWithWrongID() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("name").contains("Shift1", "Shift2", "Shift3");

        List<ShiftDTO> toReplaceShiftSecondRound = new ArrayList<>();
        List<ShiftDTO> allShift = fullLogbook.shifts();
        // replace the first and the third, removing the second and creating new one
        toReplaceShiftSecondRound.add(
                allShift.get(0).toBuilder()
                        .from(DateUtilities.toUTCString(
                                LocalTime.of(
                                        13,
                                        0
                                )
                        ))
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                13,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        // here i change the id for simulate and id not present
        toReplaceShiftSecondRound.add(
                allShift.get(2).toBuilder()
                        .id(UUID.randomUUID().toString())
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                14,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                14,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        toReplaceShiftSecondRound.add(
                ShiftDTO.builder()
                        .name("New Shift")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                15,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                15,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        ControllerLogicException replaceException = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.replaceShift(
                        newLogbookID,
                        toReplaceShiftSecondRound
                )
        );
        assertThat(replaceException.getErrorCode()).isEqualTo(-3);
        assertThat(replaceException.getErrorMessage()).containsPattern(".*Shift3.*");
    }

    @Test
    public void shiftReplaceOk() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("name").contains("Shift1", "Shift2", "Shift3");

        List<ShiftDTO> toReplaceShiftSecondRound = new ArrayList<>();
        List<ShiftDTO> allShift = fullLogbook.shifts();
        // replace the first and the third, removing the second and creating new one
        toReplaceShiftSecondRound.add(
                allShift.get(0).toBuilder()
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
                                                13,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        toReplaceShiftSecondRound.add(
                allShift.get(2).toBuilder()
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                14,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                14,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        toReplaceShiftSecondRound.add(
                ShiftDTO.builder()
                        .name("New Shift")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                15,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                15,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            toReplaceShiftSecondRound
                    );
                    return null;
                }
        );

        fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("name").contains("Shift1", "Shift3", "New Shift");
    }

    @Test
    public void shiftReplaceFailsAndRestoreOld() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );
        //replace new shifts
        replaceShifts.clear();
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift4")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                5,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                5,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift5")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                6,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                6,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift6")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                6,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                8,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        ControllerLogicException replaceException = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.replaceShift(
                        newLogbookID,
                        replaceShifts
                )
        );

        assertThat(replaceException.getErrorCode()).isEqualTo(-3);

        LogbookDTO fullLogbook = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        newLogbookID
                )
        );

        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.shifts()).extracting("name").contains("Shift1", "Shift2", "Shift3");
    }

    @Test
    public void getShiftByLocalTime() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );

        Optional<LogbookShiftDTO> foundShift = assertDoesNotThrow(
                () ->
                        logbookService.findShiftByLocalTime(
                                newLogbookID,
                                LocalTime.of(
                                        1,
                                        30
                                )
                        )

        );

        assertThat(foundShift).isNotNull();
        assertThat(foundShift.isPresent()).isTrue();
        assertThat(foundShift.get().logbook().id()).isEqualTo(newLogbookID);
        assertThat(foundShift.get().name()).isEqualTo("Shift2");
    }

    @Test
    public void getNoShiftByWrongLocalTime() {
        String newLogbookID = sharedUtilityService.getTestLogbook();
        List<ShiftDTO> replaceShifts = new ArrayList<>();
        replaceShifts.add(
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
                                                0,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift2")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                1,
                                                59
                                        )
                                )
                        )
                        .build()
        );
        replaceShifts.add(
                ShiftDTO
                        .builder()
                        .name("Shift3")
                        .from(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                0
                                        )
                                )
                        )
                        .to(
                                DateUtilities.toUTCString(
                                        LocalTime.of(
                                                2,
                                                59
                                        )
                                )
                        )
                        .build()
        );

        assertDoesNotThrow(
                () -> {
                    logbookService.replaceShift(
                            newLogbookID,
                            replaceShifts
                    );
                    return null;
                }
        );

        Optional<LogbookShiftDTO> foundShift = assertDoesNotThrow(
                () ->
                        logbookService.findShiftByLocalTime(
                                newLogbookID,
                                LocalTime.of(
                                        3,
                                        30
                                )
                        )

        );
        assertThat(foundShift).isNotNull();
        assertThat(foundShift.isPresent()).isFalse();
    }


    @Test
    public void updateLogbookTagOK() {
        String logbookID = sharedUtilityService.getTestLogbook();
        LogbookDTO logbookDTO = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookID
                )
        );

        // update with tags
        logbookDTO.tags().addAll(
                List.of(
                        TagDTO.builder()
                                .name("tag-1")
                                .build(),
                        TagDTO.builder()
                                .name("tag-2")
                                .build()
                )
        );
        UpdateLogbookDTO updatedLogbook = UpdateLogbookDTO.builder()
                .name(logbookDTO.name())
                .shifts(logbookDTO.shifts())
                .tags(logbookDTO.tags())
                .build();
        UpdateLogbookDTO finalLogbookDTO = updatedLogbook;

        assertDoesNotThrow(
                () -> logbookService.update(
                        logbookID,
                        finalLogbookDTO
                )
        );

        logbookDTO = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookID
                )
        );

        assertThat(
                logbookDTO.tags()
        ).filteredOn(
                t -> t.id() != null &&
                        (
                                t.name().equalsIgnoreCase("tag-1") ||
                                        t.name().equalsIgnoreCase("tag-2")
                        )
        ).hasSize(2);

        // update tag-2 and remove 1
        List<TagDTO> updatedTag = new ArrayList<>();
        updatedTag.add(
                updatedLogbook.tags().get(1).toBuilder()
                        .name("tag-2-updated")
                        .build()
        );
        updatedLogbook = updatedLogbook.toBuilder()
                .tags(
                        updatedTag
                ).build();
        UpdateLogbookDTO finalLogbookDTO1 = updatedLogbook;
        assertDoesNotThrow(
                () -> logbookService.update(
                        logbookID,
                        finalLogbookDTO1
                )
        );

        logbookDTO = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookID
                )
        );
        assertThat(
                logbookDTO.tags()
        )
                .hasSize(1)
                .extracting("name")
                .contains("tag-2-updated");

        // fail update tags that not exists
        updatedTag = new ArrayList<>();
        updatedTag.add(
                logbookDTO.tags().get(0).toBuilder()
                        .id("id change for simulate incoherent update")
                        .name("tag-2-updated")
                        .build()
        );
        updatedLogbook = updatedLogbook.toBuilder()
                .tags(
                        updatedTag
                ).build();
        UpdateLogbookDTO finalLogbookDTO2 = updatedLogbook;
        TagNotFound tagNotFound = assertThrows(
                TagNotFound.class,
                () -> logbookService.update(
                        logbookID,
                        finalLogbookDTO2
                )
        );
        assertThat(tagNotFound.getErrorCode()).isEqualTo(-5);
    }

    @Test
    public void updateLogbookShiftOK() {
        String logbookID = sharedUtilityService.getTestLogbook();

        // update with tags
        UpdateLogbookDTO updatedLogbook = UpdateLogbookDTO
                .builder()
                .name("name updated")
                .shifts(
                        List.of(
                                ShiftDTO.builder()
                                        .name("shift-1")
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
                                                                0,
                                                                59
                                                        )
                                                )
                                        )
                                        .build(),
                                ShiftDTO.builder()
                                        .name("shift-2")
                                        .from(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                1,
                                                                0
                                                        )
                                                )
                                        )
                                        .to(
                                                DateUtilities.toUTCString(
                                                        LocalTime.of(
                                                                1,
                                                                59
                                                        )
                                                )
                                        )
                                        .build()
                        )
                )
                .tags(
                        emptyList()
                )
                .build();

        UpdateLogbookDTO finalUpdatedLogbook = updatedLogbook;
        assertDoesNotThrow(
                () -> logbookService.update(
                        logbookID,
                        finalUpdatedLogbook
                )
        );

        LogbookDTO logbookDTO = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookID
                )
        );

        assertThat(
                logbookDTO.shifts()
        ).filteredOn(
                t -> t.id() != null &&
                        (
                                t.name().equalsIgnoreCase("shift-1") ||
                                        t.name().equalsIgnoreCase("shift-2")
                        )
        ).hasSize(2);

        // update shift-2 and remove 1
        List<ShiftDTO> updatedShift = new ArrayList<>();
        updatedShift.add(
                updatedLogbook.shifts().get(1).toBuilder()
                        .name("shift-2-updated")
                        .build()
        );
        updatedLogbook = updatedLogbook.toBuilder()
                .shifts(
                        updatedShift
                ).build();
        UpdateLogbookDTO finalLogbookDTO1 = updatedLogbook;
        assertDoesNotThrow(
                () -> logbookService.update(
                        logbookID,
                        finalLogbookDTO1
                )
        );

        logbookDTO = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookID
                )
        );
        assertThat(
                logbookDTO.shifts()
        )
                .hasSize(1)
                .extracting("name")
                .contains("shift-2-updated");

        // fail update tags that not exists
        updatedShift = new ArrayList<>();
        updatedShift.add(
                updatedLogbook.shifts().get(0).toBuilder()
                        .id("id change for simulate incoherent update")
                        .name("shift-2-updated")
                        .build()
        );
        updatedLogbook = updatedLogbook.toBuilder()
                .shifts(
                        updatedShift
                ).build();
        UpdateLogbookDTO finalLogbookDTO2 = updatedLogbook;
        ShiftNotFound tagNotFound = assertThrows(
                ShiftNotFound.class,
                () -> logbookService.update(
                        logbookID,
                        finalLogbookDTO2
                )
        );
        assertThat(tagNotFound.getErrorCode()).isEqualTo(-4);
    }

    @Test
    public void existsTagOnLogbooks() {
        String logbookIDA = sharedUtilityService.getTestLogbook("logbook-a");
        String logbookIDB = sharedUtilityService.getTestLogbook("logbook-b");
        String logbookIDC = sharedUtilityService.getTestLogbook("logbook-c");

        String tagAID = assertDoesNotThrow(
                () -> logbookService.ensureTag(logbookIDA, "tag-a")
        );
        assertThat(tagAID).isNotNull().isNotEmpty();

        Boolean exists = assertDoesNotThrow(
                () -> logbookService.tagExistForLogbook(logbookIDA, "tag-a")
        );
        assertThat(exists).isNotNull().isTrue();

        exists = assertDoesNotThrow(
                () -> logbookService.tagIdExistInAnyLogbookIds(tagAID, List.of(logbookIDA, logbookIDB))
        );
        assertThat(exists).isNotNull().isTrue();

        exists = assertDoesNotThrow(
                () -> logbookService.tagIdExistInAnyLogbookIds(tagAID, List.of(logbookIDB, logbookIDC))
        );
        assertThat(exists).isNotNull().isFalse();
    }

    @Test
    public void testAddAuthenticationToken() {
        String logbookIDA = sharedUtilityService.getTestLogbook("logbook-a");
        var success = assertDoesNotThrow(
                () -> logbookService.addNewAuthenticationToken(
                        logbookIDA,
                        NewAuthenticationTokenDTO
                                .builder()
                                .name("token-a")
                                .expiration(
                                        LocalDate.of(
                                                2023,
                                                12,
                                                31
                                        )
                                )
                                .build()
                )
        );

        LogbookDTO lb = assertDoesNotThrow(
                () -> logbookService.getLogbook(
                        logbookIDA
                )
        );

        assertThat(lb).isNotNull();
    }

    @Test
    public void testAddAuthenticationTokenFailWIthSameName() {
        String logbookIDA = sharedUtilityService.getTestLogbook("logbook-a");
        var success = assertDoesNotThrow(
                () -> logbookService.addNewAuthenticationToken(
                        logbookIDA,
                        NewAuthenticationTokenDTO
                                .builder()
                                .name("token-a")
                                .expiration(
                                        LocalDate.of(
                                                2023,
                                                12,
                                                31
                                        )
                                )
                                .build()
                )
        );

        ControllerLogicException exception = assertThrows(
                ControllerLogicException.class,
                () -> logbookService.addNewAuthenticationToken(
                        logbookIDA,
                        NewAuthenticationTokenDTO
                                .builder()
                                .name("token-a")
                                .expiration(
                                        LocalDate.of(
                                                2023,
                                                12,
                                                31
                                        )
                                )
                                .build()
                )
        );

        assertThat(exception.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void testFindEarliestNShiftDateForOneLogbooks() {
        LocalTime[][] shiftRanges = new LocalTime[3][2];
        shiftRanges[0][0] = LocalTime.of(0, 0);
        shiftRanges[0][1] = LocalTime.of(7, 59);
        shiftRanges[1][0] = LocalTime.of(8, 0);
        shiftRanges[1][1] = LocalTime.of(12, 59);
        shiftRanges[2][0] = LocalTime.of(13, 0);
        shiftRanges[2][1] = LocalTime.of(17, 59);
        String newLogbookID = sharedUtilityService.getTestLogbook();
        logbookService.replaceShift(
                newLogbookID,
                List.of(
                        ShiftDTO.builder().name("Shift1").from(DateUtilities.toUTCString(shiftRanges[0][0])).to(DateUtilities.toUTCString(shiftRanges[0][1])).build(),
                        ShiftDTO.builder().name("Shift2").from(DateUtilities.toUTCString(shiftRanges[1][0])).to(DateUtilities.toUTCString(shiftRanges[1][1])).build(),
                        ShiftDTO.builder().name("Shift3").from(DateUtilities.toUTCString(shiftRanges[2][0])).to(DateUtilities.toUTCString(shiftRanges[2][1])).build()
                )
        );

        var earliestDate20 = logbookService.findEarliestNShiftForLogbooks(1, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate20).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 0)));

        // now get the last 2 shifts and return the date in location [1][0]
        var earliestDate10 = logbookService.findEarliestNShiftForLogbooks(2, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate10).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now(), LocalTime.of(8, 0)));

        // now return the last 3 shifts and return the date in location [0][0]
        var earliestDate00 = logbookService.findEarliestNShiftForLogbooks(3, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate00).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now(), LocalTime.of(0, 0)));

        // now return the last 4 shifts and return the date in location [2][0] of the day before
        var earliestDateBefore = logbookService.findEarliestNShiftForLogbooks(4, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDateBefore).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(13, 0)));
    }

    @Test
    public void testFindEarliestNShiftDateForOneLogbooksWithHoles() {
        LocalTime[][] shiftRanges = new LocalTime[3][2];
        shiftRanges[0][0] = LocalTime.of(0, 0);
        shiftRanges[0][1] = LocalTime.of(7, 59);
        shiftRanges[1][0] = LocalTime.of(8, 0);
        shiftRanges[1][1] = LocalTime.of(12, 59);
        shiftRanges[2][0] = LocalTime.of(14, 0); // Hole between 13:00 and 14:00
        shiftRanges[2][1] = LocalTime.of(17, 59);
        String newLogbookID = sharedUtilityService.getTestLogbook();
        logbookService.replaceShift(
                newLogbookID,
                List.of(
                        ShiftDTO.builder().name("Shift1").from(DateUtilities.toUTCString(shiftRanges[0][0])).to(DateUtilities.toUTCString(shiftRanges[0][1])).build(),
                        ShiftDTO.builder().name("Shift2").from(DateUtilities.toUTCString(shiftRanges[1][0])).to(DateUtilities.toUTCString(shiftRanges[1][1])).build(),
                        ShiftDTO.builder().name("Shift3").from(DateUtilities.toUTCString(shiftRanges[2][0])).to(DateUtilities.toUTCString(shiftRanges[2][1])).build()
                )
        );

        var earliestDateBefore = logbookService.findEarliestNShiftForLogbooks(4, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDateBefore).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(8, 0)));
        earliestDateBefore = logbookService.findEarliestNShiftForLogbooks(3, List.of(newLogbookID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDateBefore).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(1), LocalTime.of(14, 0)));
    }

    @Test
    public void testFindEarliestNShiftDateForMultipleLogbooks() {
        LocalTime[][] shiftRanges = new LocalTime[3][2];
        shiftRanges[0][0] = LocalTime.of(0, 0);
        shiftRanges[0][1] = LocalTime.of(7, 59);
        shiftRanges[1][0] = LocalTime.of(8, 0);
        shiftRanges[1][1] = LocalTime.of(12, 59);
        shiftRanges[2][0] = LocalTime.of(13, 0);
        shiftRanges[2][1] = LocalTime.of(17, 59);
        String newLogbookAID = sharedUtilityService.getTestLogbook("new-logbooks-a");
        logbookService.replaceShift(
                newLogbookAID,
                List.of(
                        ShiftDTO.builder().name("Shift2A").from(DateUtilities.toUTCString(shiftRanges[1][0])).to(DateUtilities.toUTCString(shiftRanges[1][1])).build(),
                        ShiftDTO.builder().name("Shift3A").from(DateUtilities.toUTCString(shiftRanges[2][0])).to(DateUtilities.toUTCString(shiftRanges[2][1])).build()
                )
        );

        String newLogbookBID = sharedUtilityService.getTestLogbook("new-logbooks-b");
        logbookService.replaceShift(
                newLogbookBID,
                List.of(
                        ShiftDTO.builder().name("Shift1B").from(DateUtilities.toUTCString(shiftRanges[0][0])).to(DateUtilities.toUTCString(shiftRanges[0][1])).build()
                )
        );

        var earliestDate00 = logbookService.findEarliestNShiftForLogbooks(1, List.of(newLogbookAID, newLogbookBID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate00).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now(), shiftRanges[0][0]));

        // now get the last 2 shifts and return the date in location [1][0]
        var earliestDate00DayMinus1 = logbookService.findEarliestNShiftForLogbooks(2, List.of(newLogbookAID, newLogbookBID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate00DayMinus1).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(1), shiftRanges[0][0]));

        // now return the last 3 shifts and return the date in location [0][0]
        var earliestDate00DayMinus2 = logbookService.findEarliestNShiftForLogbooks(3, List.of(newLogbookAID, newLogbookBID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate00DayMinus2).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(2), shiftRanges[0][0]));

        // now return the last 4 shifts and return the date in location [2][0] of the day before
        var earliestDate00DayMinus3 = logbookService.findEarliestNShiftForLogbooks(4, List.of(newLogbookAID, newLogbookBID), LocalDateTime.of(LocalDate.now(), LocalTime.of(13, 30)));
        assertThat(earliestDate00DayMinus3).isNotNull().isEqualTo(LocalDateTime.of(LocalDate.now().minusDays(3), shiftRanges[0][0]));
    }
}
