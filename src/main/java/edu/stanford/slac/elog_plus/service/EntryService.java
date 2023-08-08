package edu.stanford.slac.elog_plus.service;

import com.github.javafaker.Faker;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper;
import edu.stanford.slac.elog_plus.api.v1.mapper.QueryParameterMapper;
import edu.stanford.slac.elog_plus.api.v1.mapper.ShiftMapper;
import edu.stanford.slac.elog_plus.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.exception.EntryNotFound;
import edu.stanford.slac.elog_plus.exception.ShiftNotFound;
import edu.stanford.slac.elog_plus.exception.SupersedeAlreadyCreated;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Summarizes;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import edu.stanford.slac.elog_plus.utility.StringUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static edu.stanford.slac.elog_plus.exception.Utility.assertion;
import static edu.stanford.slac.elog_plus.exception.Utility.wrapCatch;

@Service
@Log4j2
@AllArgsConstructor
public class EntryService {
    final private EntryRepository entryRepository;
    final private LogbookService logbookService;
    final private AttachmentService attachmentService;

    public List<EntrySummaryDTO> searchAll(QueryWithAnchorDTO queryWithAnchorDTO) {
        List<Entry> found = wrapCatch(
                () -> entryRepository.searchAll(
                        QueryParameterMapper.INSTANCE.fromDTO(
                                queryWithAnchorDTO
                        )
                ),
                -1,
                "LogService::searchAll"
        );
        return found.stream().map(
                log -> {
                    var shift = logbookService.findShiftByLocalTimeWithLogbookName(
                            log.getLogbook(),
                            log.getEventAt().toLocalTime()
                    );
                    EntrySummaryDTO es = EntryMapper.INSTANCE.toSearchResultFromDTO(
                            log,
                            attachmentService
                    );
                    return es.toBuilder()
                            .shift(
                                    shift.orElse(null)
                            ).build();
                }

        ).collect(Collectors.toList());
    }

    /**
     * Find the entry id by shift name ad a date
     *
     * @param shiftId the shift name
     * @param date    the date of the summary to find
     * @return the id of te summary
     */
    public String findSummaryIdForShiftIdAndDate(String shiftId, LocalDate date) {
        Optional<Entry> summary = wrapCatch(
                () -> entryRepository.findBySummarizes_ShiftIdAndSummarizes_Date(
                        shiftId,
                        date
                ),
                -1,
                "EntryService:getSummaryIdForShiftIdAndDate"
        );
        return summary.orElseThrow(
                () -> EntryNotFound.entryNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("EntryService:getSummaryIdForShiftIdAndDat")
                        .build()
        ).getId();
    }

    /**
     * Create a new log entry
     *
     * @param entryToImport is a new log information
     * @return the id of the newly created log
     */
    @Transactional()
    public String createNew(EntryImportDTO entryToImport, List<String> attachments) {
        return createNew(
                EntryMapper.INSTANCE.fromDTO(
                        entryToImport,
                        attachments
                )
        );
    }

    /**
     * Create a new log entry
     *
     * @param entryNewDTO is a new log information
     * @return the id of the newly created log
     */
    @Transactional(propagation = Propagation.NESTED)
    public String createNew(EntryNewDTO entryNewDTO) {
        Faker faker = new Faker();
        return createNew(
                EntryMapper.INSTANCE.fromDTO(
                        entryNewDTO,
                        faker.name().firstName(),
                        faker.name().lastName(),
                        faker.name().username()
                )
        );
    }

    /**
     * Create a new log entry
     *
     * @param newEntry is a new log information
     * @return the id of the newly created log
     */
    @Transactional(propagation = Propagation.NESTED)
    public String createNew(Entry newEntry) {
        //get and check for logbook
        Entry finalNewEntry = newEntry;
        LogbookDTO lb =
                wrapCatch(
                        () -> logbookService.getLogbookByName(finalNewEntry.getLogbook()),
                        -1,
                        "EntryService:createNew"
                );
        // check for summarization
        checkForSummarization(lb, newEntry.getSummarizes());

        // normalize tag name
        if (newEntry.getTags() != null) {
            List<String> tagsNormalizedNames = newEntry
                    .getTags()
                    .stream()
                    .map(
                            StringUtilities::tagNameNormalization
                    )
                    .toList();
            newEntry.setTags(
                    tagsNormalizedNames
            );
        }

        newEntry
                .getTags()
                .forEach(
                        tagName -> {
                            if (
                                    lb.tags().stream().noneMatch(
                                            t -> t.name().compareTo(tagName) == 0)
                            ) {
                                // we need to create a tag for the entry
                                String newTagID = wrapCatch(
                                        () -> {
                                            logbookService.ensureTag(
                                                    lb.id(),
                                                    tagName
                                            );
                                            return null;
                                        },
                                        -2,
                                        "EntryService:createNew"
                                );
                                log.info("Tag automatically created for the logbook {} with name {}", lb.name(), tagName);
                            }
                        }
                );

        newEntry
                .getAttachments()
                .forEach(
                        attachmentID -> {
                            if (!attachmentService.exists(attachmentID)) {
                                String error = String.format("The attachment id '%s' has not been found", attachmentID);
                                throw ControllerLogicException.of(
                                        -3,
                                        error,
                                        "LogService::createNew"
                                );
                            }
                        }
                );

        //sanitize title and text
        Entry finalNewEntry1 = newEntry;
        assertion(
                () -> (finalNewEntry1.getTitle() != null && !finalNewEntry1.getTitle().isEmpty()),
                ControllerLogicException
                        .builder()
                        .errorCode(-4)
                        .errorMessage("The title is mandatory")
                        .errorDomain("LogService::createNew")
                        .build()
        );
        newEntry.setTitle(
                StringUtilities.sanitizeEntryTitle(newEntry.getTitle())
        );
        assertion(
                () -> (finalNewEntry1.getText() != null),
                ControllerLogicException
                        .builder()
                        .errorCode(-4)
                        .errorMessage("The body is mandatory also if empty")
                        .errorDomain("LogService::createNew")
                        .build()
        );
        newEntry.setText(
                StringUtilities.sanitizeEntryText(newEntry.getText())
        );

        // other check
        Entry finalNewEntryToSave = newEntry;
        newEntry =
                wrapCatch(
                        () -> entryRepository.insert(
                                finalNewEntryToSave
                        ),
                        -5,
                        "LogService::createNew"
                );
        log.info("New entry '{}' created", newEntry.getTitle());
        return newEntry.getId();
    }

    public EntryDTO getFullLog(String id) {
        return getFullLog(id, Optional.of(false), Optional.of(false), Optional.of(false));
    }

    /**
     * return the full log
     *
     * @param id                  the unique identifier of the log
     * @param includeFollowUps    if true the result will include the follow-up logs
     * @param includeFollowingUps if true the result will include all the following up of this
     * @param followHistory       if true the result will include the log history
     * @return the full log description
     */
    public EntryDTO getFullLog(String
                                       id, Optional<Boolean> includeFollowUps, Optional<Boolean> includeFollowingUps, Optional<Boolean> followHistory) {
        EntryDTO result = null;
        Optional<Entry> foundEntry =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::getFullLog"
                );
        assertion(
                foundEntry::isPresent,
                EntryNotFound.entryNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogService::getFullLog")
                        .build()
        );

        result = EntryMapper.INSTANCE.fromModel(
                foundEntry.get(),
                attachmentService
        );

        if (includeFollowUps.isPresent() && includeFollowUps.get()) {
            List<EntryDTO> list = new ArrayList<>(foundEntry.get().getFollowUps().size());
            for (String fID : foundEntry.get().getFollowUps()) {
                list.add(getFullLog(fID));

            }
            result = result.toBuilder()
                    .followUps(list)
                    .build();
        }

        if (includeFollowingUps.isPresent() && includeFollowingUps.get()) {
            Optional<Entry> followingLog = wrapCatch(
                    () -> entryRepository.findByFollowUpsContains(id),
                    -3,
                    "LogService::getFullLog"
            );
            if (followingLog.isPresent()) {
                result = result.toBuilder()
                        .followingUp(
                                followingLog.map(
                                        l -> EntryMapper.INSTANCE.fromModel(l, attachmentService)
                                ).orElse(null)
                        )
                        .build();
            }
        }

        // fill history
        if (followHistory.isPresent() && followHistory.get()) {
            // load all the history
            List<EntryDTO> logHistory = new ArrayList<>();
            getLogHistory(id, logHistory);
            if (!logHistory.isEmpty()) {
                result = result.toBuilder()
                        .history(logHistory)
                        .build();
            }
        }

        // fill shift
        Optional<ShiftDTO> entryShift = logbookService.findShiftByLocalTimeWithLogbookName(
                foundEntry.get().getLogbook(),
                foundEntry.get().getEventAt().toLocalTime()
        );

        return result.toBuilder()
                .shift(entryShift.orElse(null))
                .build();
    }

    /**
     * Return the previous log in the history, the superseded log is returned without attachment
     *
     * @param newestLogID is the log of the root release for which we want the history
     * @return the log superseded byt the one identified by newestLogID
     */
    public EntryDTO getSuperseded(String newestLogID) {
        Optional<Entry> foundLog =
                wrapCatch(
                        () -> entryRepository.findBySupersedeBy(newestLogID),
                        -1,
                        "LogService::getLogHistory"
                );
        return foundLog.map(EntryMapper.INSTANCE::fromModelNoAttachment).orElse(null);
    }

    /**
     * Return all the history of the log from the newest one passed in input until the last
     *
     * @param newestLogID the log of the newest id
     * @param history     the list of the log until the last, from the one identified by newestLogID
     */
    public void getLogHistory(String newestLogID, List<EntryDTO> history) {
        if (history == null) return;
        EntryDTO prevInHistory = getSuperseded(newestLogID);
        if (prevInHistory == null) return;

        history.add(prevInHistory);
        getLogHistory(prevInHistory.id(), history);
    }

    /**
     * Create a new supersede of the log
     *
     * @param id     the identifier of the log to supersede
     * @param newLog the content of the new supersede log
     * @return the id of the new supersede log
     */
    @Transactional
    public String createNewSupersede(String id, EntryNewDTO newLog) {
        Optional<Entry> supersededLog =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::createNewSupersede"
                );
        assertion(
                supersededLog::isPresent,
                EntryNotFound.entryNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogService::createNewSupersede")
                        .build()
        );
        Entry l = supersededLog.get();
        assertion(
                () -> (l.getSupersedeBy() == null ||
                        l.getSupersedeBy().isEmpty())
                ,
                SupersedeAlreadyCreated.supersedeAlreadyCreatedBuilder()
                        .errorCode(-3)
                        .errorDomain("LogService::createNewSupersede")
                        .build()
        );
        //create supersede
        String newLogID = createNew(newLog);
        // update supersede
        l.setSupersedeBy(newLogID);
        wrapCatch(
                () -> entryRepository.save(l),
                -4,
                "LogService::createNewSupersede"
        );
        log.info("New supersede for '{}' created with id '{}'", l.getTitle(), newLogID);
        return newLogID;
    }

    /**
     * Create a new follow-up for a specific log
     *
     * @param id     the id for the log the need to be followed
     * @param newLog the content of the new follow-up log
     * @return the id of the new follow-up log
     */
    @Transactional
    public String createNewFollowUp(String id, EntryNewDTO newLog) {
        Optional<Entry> rootLog =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::createNewFollowUp"
                );
        assertion(
                rootLog::isPresent,
                -2,
                "The log to follow-up has not been found",
                "LogService::createNewFollowUp"
        );
        Entry l = rootLog.get();
        String newFollowupLogID = createNew(newLog);
        // update supersede
        l.getFollowUps().add(newFollowupLogID);
        wrapCatch(
                () -> entryRepository.save(l),
                -4,
                "LogService::createNewSupersede"
        );
        log.info("New followup for '{}' created with id '{}'", l.getTitle(), newFollowupLogID);
        return newFollowupLogID;
    }

    /**
     * Return all the follow-up log for a specific one
     *
     * @param id the id of the log parent of the follow-up
     * @return the list of all the followup of the specific log identified by the id
     */
    public List<EntrySummaryDTO> getAllFollowUpForALog(String id) {
        Optional<Entry> rootLog =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::getAllFollowUpForALog"
                );
        assertion(
                rootLog::isPresent,
                EntryNotFound.entryNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogService::getAllFollowUpForALog")
                        .build()
        );
        assertion(
                () -> (rootLog.get().getFollowUps().size() > 0),
                -3,
                "The log has not been found",
                "LogService::getAllFollowUpForALog"
        );
        List<Entry> followUp =
                wrapCatch(
                        () -> entryRepository.findAllByIdIn(rootLog.get().getFollowUps()),
                        -1,
                        "LogService::getAllFollowUpForALog"
                );
        return followUp
                .stream()
                .map(
                        l -> EntryMapper.INSTANCE.toSearchResultFromDTO(l, attachmentService)
                )
                .collect(Collectors.toList());
    }

    public List<String> getAllTags() {
        return wrapCatch(
                entryRepository::getAllTags,
                -1,
                "LogService::getAllTags"
        );
    }

    /**
     * In case of summary information this wil check and in case will file the exception
     *
     * @param lb        the logbook ofr the current entry
     * @param summarize the summarization information
     */
    private void checkForSummarization(LogbookDTO lb, Summarizes summarize) {
        if (summarize == null) return;
        assertion(
                () -> ((lb.shifts() != null && !lb.shifts().isEmpty())),
                ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("The logbook has not any shift")
                        .errorDomain("EntryService:checkForSummarization")
                        .build()
        );

        assertion(
                () -> (summarize.getShiftId() != null && !summarize.getShiftId().isEmpty()),
                ControllerLogicException
                        .builder()
                        .errorCode(-2)
                        .errorMessage("Shift name is mandatory on summarizes object")
                        .errorDomain("EntryService:checkForSummarization")
                        .build()
        );
        assertion(
                () -> (summarize.getDate() != null),
                ControllerLogicException
                        .builder()
                        .errorCode(-3)
                        .errorMessage("Shift date is mandatory on summarizes object")
                        .errorDomain("EntryService:checkForSummarization")
                        .build()
        );
        List<ShiftDTO> allShift = lb.shifts();
        allShift.stream().filter(
                s -> s.id().compareToIgnoreCase(summarize.getShiftId()) == 0
        ).findAny().orElseThrow(
                () -> ShiftNotFound.shiftNotFoundBuilder()
                        .errorCode(-4)
                        .shiftName(summarize.getShiftId())
                        .errorDomain("EntryService:checkForSummarization")
                        .build()
        );
    }

    /**
     * check if an entry exists using the origin id
     *
     * @param originId the origin id
     */
    public boolean existsByOriginId(String originId) {
        return wrapCatch(
                () -> entryRepository.existsByOriginId(originId),
                -1,
                "EntryService::existsByOriginId"
        );
    }
}
