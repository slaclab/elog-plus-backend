package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper;
import edu.stanford.slac.elog_plus.api.v1.mapper.QueryParameterMapper;
import edu.stanford.slac.elog_plus.exception.*;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.model.Summarizes;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import edu.stanford.slac.elog_plus.utility.StringUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;
import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;
import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF;
import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF_ID;
import static edu.stanford.slac.elog_plus.config.CacheConfig.ENTRIES;
import static java.util.Collections.emptyList;

@Service
@Log4j2
@AllArgsConstructor
public class EntryService {
    final private QueryParameterMapper queryParameterMapper;
    final private EntryRepository entryRepository;
    final private LogbookService logbookService;
    final private AttachmentService attachmentService;
    final private EntryMapper entryMapper;
    final private MailService mailService;
    final private PeopleGroupService peopleGroupService;

    /**
     * Return the logbook id for the entry
     *
     * @param id the id of the entry for which we need to return the logbook id
     * @return the log book id which the entry belongs
     */
    public List<String> getLogbooksForAnEntryId(String id) {
        return getFullEntry(id)
                .logbooks()
                .stream()
                .map(
                        LogbookSummaryDTO::id
                )
                .toList();
    }

    /**
     * Perform the search operation on all the entries
     *
     * @param queryWithAnchorDTO the parameter for the search operation
     * @return the list of found entries that matches the input parameter
     */
    public List<EntrySummaryDTO> findAll(QueryWithAnchorDTO queryWithAnchorDTO) {
        // check if there is some authorized logbook
        if (queryWithAnchorDTO.logbooks() == null) {
            // in this case user is not authorize on any logbook
            return emptyList();
        }

        // check if we need to return the last n shifts entries
        if(queryWithAnchorDTO.lastNShifts() != null) {
            if (queryWithAnchorDTO.lastNShifts() > 0) {
                var endDateToUse = queryWithAnchorDTO.endDate()==null?LocalDateTime.now():queryWithAnchorDTO.endDate();
                // find the date of the last n shifts
                var fromDateByTheNShifts = logbookService.findEarliestNShiftForLogbooks(queryWithAnchorDTO.lastNShifts(), queryWithAnchorDTO.logbooks(), endDateToUse);

                // set new date query range
                queryWithAnchorDTO = queryWithAnchorDTO.toBuilder()
                        .startDate(fromDateByTheNShifts)
                        .endDate(endDateToUse)
                        .build();
            } else {
                throw ControllerLogicException.builder()
                        .errorCode(-1)
                        .errorMessage("The lastNShifts parameter need to be greater than 0")
                        .errorDomain("LogService::findAll")
                        .build();
            }
        }

        QueryWithAnchorDTO finalQueryWithAnchorDTO = queryWithAnchorDTO;
        List<Entry> found = wrapCatch(
                () -> entryRepository.searchAll(
                        queryParameterMapper.fromDTO(
                                finalQueryWithAnchorDTO
                        )
                ),
                -1,
                "LogService::searchAll"
        );
        return found.stream().map(
                entry -> {
                    EntrySummaryDTO es = entryMapper.toSearchResult(
                            entry
                    );
                    return es.toBuilder()
                            .shifts(
                                    getShiftsForEntry(
                                            es.logbooks().stream().map(LogbookSummaryDTO::id).toList(),
                                            es.eventAt()
                                    )
                            )
                            .build();
                }

        ).collect(Collectors.toList());
    }

    /**
     * Return the shift that are in common with all the logbooks in input
     * in the same time
     *
     * @param logbookIds the list of the logbook ids
     * @param eventAt    the time which we need the shift
     * @return the shift list
     */
    private List<LogbookShiftDTO> getShiftsForEntry(List<String> logbookIds, LocalDateTime eventAt) {
        List<LogbookShiftDTO> shifts = new ArrayList<>();
        if (logbookIds == null || logbookIds.isEmpty()) return shifts;
        if (eventAt == null) return shifts;
        for (String logbookId :
                logbookIds) {
            var shiftDTO = wrapCatch(
                    () -> logbookService.findShiftByLocalTime(
                            logbookId,
                            eventAt.toLocalTime()
                    ),
                    -1,
                    "LogService::getShiftsForEntry"
            );
            //add in case the shift is present
            shiftDTO.ifPresent(shifts::add);
        }
        return shifts;
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
     * @param entryNewDTO is a new log information
     * @return the id of the newly created log
     */
    @Transactional
    public String createNew(EntryNewDTO entryNewDTO, PersonDTO personDTO) {
        return createNew(
                toModelWithCreator(
                        entryNewDTO,
                        personDTO
                )
        );
    }

    /**
     * Create a new log entry
     *
     * @param entryNewDTO is a new log information
     * @param creator     the creator of the new log
     * @return the id of the newly created log
     */
    public Entry toModelWithCreator(EntryNewDTO entryNewDTO, PersonDTO creator) {
        String firstname = "";
        String lastName = "";
        String[] splittedGecos = creator.gecos().split(" ");
        if (splittedGecos.length >= 2) {
            // Join all parts except the last one as the first name
            firstname = String.join(" ", Arrays.copyOfRange(splittedGecos, 0, splittedGecos.length - 1));
            // The last element is the last name
            lastName = splittedGecos[splittedGecos.length - 1];
        } else if (splittedGecos.length == 1) {
            // If there's only one element, treat it as the first name
            firstname = splittedGecos[0];
        }
        return entryMapper.fromDTO(
                entryNewDTO,
                firstname,
                lastName,
                creator.mail()
        );
    }

    /**
     * Create a new log entry
     *
     * @param newEntry is a new log information
     * @return the id of the newly created log
     */
    @Transactional()
    public String createNew(Entry newEntry) {
        //get and check for logbooks
        Entry finalNewEntry = newEntry;

        // be sure that entry has not double logbooks, remove the double logbooks id
//        newEntry.setLogbooks(
//                newEntry.getLogbooks().stream().distinct().toList()
//        );

        // verify that all logbook exists
        assertion(
                () -> (finalNewEntry.getLogbooks() != null && !finalNewEntry.getLogbooks().isEmpty()),
                ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("The logbooks are mandatory, entry need to belong to almost one logbook")
                        .errorDomain("LogService::createNew")
                        .build()
        );

        if (finalNewEntry.getSummarizes() != null) {
            assertion(
                    () -> (finalNewEntry.getLogbooks().size() == 1),
                    ControllerLogicException
                            .builder()
                            .errorCode(-2)
                            .errorMessage("An entry that is a summary's shift needs to belong only to one logbook")
                            .errorDomain("LogService::createNew")
                            .build()
            );

            //check for shift
            LogbookDTO lb =
                    wrapCatch(
                            () -> logbookService.getLogbook(finalNewEntry.getLogbooks().get(0)),
                            -3,
                            "EntryService:createNew"
                    );
            // check for summarization
            checkForSummarization(lb, newEntry.getSummarizes());
        } else {
            // check  all logbooks
            newEntry.getLogbooks().forEach(
                    logbookId -> {
                        assertion(
                                () -> logbookService.existById(logbookId),
                                LogbookNotFound
                                        .logbookNotFoundBuilder()
                                        .errorCode(-4)
                                        .errorDomain("LogService::createNew")
                                        .build()
                        );
                    }
            );
        }
        // check for attachment
        newEntry
                .getAttachments()
                .forEach(
                        attachmentID -> {
                            // check for presence of the attachment
                            assertion(
                                    AttachmentNotFound.attachmentNotFoundBuilder()
                                            .errorCode(-3)
                                            .attachmentID(attachmentID)
                                            .errorDomain("LogService::createNew")
                                            .build(),
                                    () -> attachmentService.exists(attachmentID)
                            );
                        }
                );
        // check for tags
        newEntry
                .getTags()
                .forEach(
                        tagId -> {
                            assertion(
                                    () -> logbookService.tagIdExistInAnyLogbookIds
                                            (
                                                    tagId,
                                                    finalNewEntry.getLogbooks()
                                            ),
                                    TagNotFound.tagNotFoundBuilder()
                                            .errorCode(-4)
                                            .tagName(tagId)
                                            .errorDomain("LogService::createNew")
                                            .build()
                            );
                        }
                );

        //sanitize title and text
        Entry finalNewEntry1 = newEntry;

        assertion(
                () -> (finalNewEntry1.getTitle() != null && !finalNewEntry1.getTitle().

                        isEmpty()),
                ControllerLogicException
                        .builder()
                        .errorCode(-4)
                        .errorMessage("The title is mandatory")
                        .errorDomain("LogService::createNew")
                        .build()
        );
//        newEntry.setTitle(
//                StringUtilities.sanitizeEntryTitle(newEntry.getTitle())
//        );

        assertion(
                () -> (finalNewEntry1.getText() != null),
                ControllerLogicException
                        .builder()
                        .errorCode(-4)
                        .errorMessage("The body is mandatory also if empty")
                        .errorDomain("LogService::createNew")
                        .build()
        );

        // remove the invalid references
        filterOutInvalidReference(newEntry);

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
        // send email notification
        if (newEntry.getUserIdsToNotify() != null && !newEntry.getUserIdsToNotify().isEmpty()) {
            log.info("Sending email notification for new entry '{}'", newEntry.getTitle());

            // validate the email addresses
            var peopleList = newEntry.getUserIdsToNotify().stream().map(
                    peopleGroupService::findPersonByEMail
            ).toList();

            // send the emails
            mailService.sentNewEmailNotification(
                    peopleList,
                    newEntry
            );
        }
        return newEntry.getId();
    }

    /**
     * Return the ids of the logbooks which the parent entry is associated
     *
     * @param id the attachment id
     * @return
     */
    public List<EntrySummaryDTO> getEntriesThatOwnTheAttachment(String id) {
        return wrapCatch(
                () -> entryRepository.findAllByAttachmentsContains(id),
                -1,
                "LogService::createNew"
        ).stream()
                .map(
                        entryMapper::toSearchResult
                ).toList();
    }

    /**
     * Create and manage references for the entry to create
     * <p>
     * the reference will be checked for the existence
     *
     * @param newEntry the new entry that need to be created
     */
    public void filterOutInvalidReference(Entry newEntry) {
        if (newEntry.getReferences() == null || newEntry.getReferences().isEmpty()) return;
        List<String> validReference = new ArrayList<>();
        for (String referencedEntryId :
                newEntry.getReferences()) {
            // check for the reference entry if exists
            if (
                    wrapCatch(
                            () -> entryRepository.existsById(referencedEntryId),
                            -1,
                            "EntryService::manageNewEntryReferences"
                    )
            ) {
                // referenced entry exists
                validReference.add(referencedEntryId);
            }
        }
        newEntry.setReferences(validReference);
    }

    /**
     * Get the full entry
     *
     * @param id unique id of the entry
     * @return the full entry
     */
    public EntryDTO getFullEntry(String id) {
        return getFullEntry(
                id,
                Optional.of(false),
                Optional.of(false),
                Optional.of(false),
                Optional.of(false),
                Optional.of(false),
                Optional.of(false)
        );
    }

    /**
     * Return the full entry
     *
     * @param id                  the unique identifier of the log
     * @param includeFollowUps    if true the result will include the follow-up logs
     * @param includeFollowingUps if true the result will include all the following up of this
     * @param followHistory       if true the result will include the log history
     * @return the full entry
     */
    public EntryDTO getFullEntry(
            String id,
            Optional<Boolean> includeFollowUps,
            Optional<Boolean> includeFollowingUps,
            Optional<Boolean> followHistory,
            Optional<Boolean> includeReferences,
            Optional<Boolean> includeReferencedBy,
            Optional<Boolean> includeSupersededBy) {
        EntryDTO result = null;
        Entry foundEntry =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::getFullEntry"
                ).orElseThrow(
                        () -> EntryNotFound.entryNotFoundBuilder()
                                .errorCode(-2)
                                .errorDomain("LogService::getFullEntry")
                                .build()
                );

        // convert to model
        result = entryMapper.fromModel(
                foundEntry
        );

        if (includeFollowUps.isPresent() && includeFollowUps.get()) {
            List<EntrySummaryDTO> list = getAllFollowUpForALog(id);
            result = result.toBuilder()
                    .followUps(list)
                    .build();
        }

        if (includeFollowingUps.isPresent() && includeFollowingUps.get()) {
            Optional<Entry> followingLog = wrapCatch(
                    // fin followUps only on the last version (supersededBy is null) of the entry
                    () -> entryRepository.findByFollowUpsContainsAndSupersededByIsNull(id),
                    -3,
                    "LogService::getFullEntry"
            );
            if (followingLog.isPresent()) {
                result = result.toBuilder()
                        .followingUp(
                                followingLog.map(
                                        entryMapper::toSearchResult
                                ).orElse(null)
                        )
                        .build();
            }
        }

        // fill history
        if (followHistory.isPresent() && followHistory.get()) {
            // load all the history
            List<EntrySummaryDTO> logHistory = new ArrayList<>();
            getLogHistory(id, logHistory);
            if (!logHistory.isEmpty()) {
                result = result.toBuilder()
                        .history(logHistory)
                        .build();
            }
        }

        if (includeReferences.orElse(false)) {
            // fill the references field
            result = result.toBuilder()
                    .references(
                            foundEntry.getReferences()
                                    .stream()
                                    .map(
                                            refId -> wrapCatch(
                                                    () -> entryRepository.findById(refId)
                                                            .map(
                                                                    entryMapper::toSearchResult
                                                            )
                                                            .orElseThrow(
                                                                    () -> EntryNotFound.entryNotFoundBuilder()
                                                                            .errorCode(-4)
                                                                            .errorDomain("LogService::getFullEntry")
                                                                            .build()
                                                            ),
                                                    -5,
                                                    "LogService::getFullEntry"
                                            )
                                    ).toList()
                    )
                    .build();
        } else {
            result = result.toBuilder()
                    .references(emptyList())
                    .build();
        }

        if (includeReferencedBy.orElse(false)) {
            // fill the referencedBy field
            result = result.toBuilder()
                    .referencedBy(
                            wrapCatch(
                                    () -> entryRepository.findAllByReferencesContainsAndSupersededByExists(foundEntry.getId(), false)
                                            .stream()
                                            .map(
                                                    entryMapper::toSearchResult
                                            )
                                            .toList(),
                                    -6,
                                    "EntryMapper::getFullEntry"
                            )
                    )
                    .build();
        } else {
            result = result.toBuilder()
                    .referencedBy(emptyList())
                    .build();
        }

        if (includeSupersededBy.orElse(false) && foundEntry.getSupersededBy() != null) {
            // fill the referencedBy field
            result = result.toBuilder()
                    .supersededBy(
                            wrapCatch(
                                    () -> entryRepository.findById(foundEntry.getSupersededBy())
                                            .map(entryMapper::toSearchResult)
                                            .orElse(null),
                                    -7,
                                    "EntryMapper::getFullEntry"
                            )
                    )
                    .build();
        }

        // fill shift
        return result.toBuilder()
                .shifts(
                        getShiftsForEntry(
                                foundEntry.getLogbooks(),
                                foundEntry.getEventAt()
                        )
                )
                .build();
    }

    /**
     * Return the previous log in the history, the superseded log is returned without attachment
     *
     * @param newestLogID is the log of the root release for which we want the history
     * @return the log superseded byt the one identified by newestLogID
     */
    public EntrySummaryDTO getSuperseded(String newestLogID) {
        Optional<Entry> foundLog =
                wrapCatch(
                        () -> entryRepository.findBySupersededBy(newestLogID),
                        -1,
                        "LogService::getSuperseded"
                );
        return foundLog.map(entryMapper::toSearchResult).orElse(null);
    }

    /**
     * Return all the history of the log from the newest one passed in input until the last
     *
     * @param newestLogID the log of the newest id
     * @param history     the list of the log until the last, from the one identified by newestLogID
     */
    public void getLogHistory(String newestLogID, List<EntrySummaryDTO> history) {
        if (history == null) return;
        EntrySummaryDTO prevInHistory = getSuperseded(newestLogID);
        if (prevInHistory == null) return;

        history.add(prevInHistory);
        getLogHistory(prevInHistory.id(), history);
    }

    /**
     * Create a new supersede of the log
     *
     * @param entryId the identifier of the log to supersede
     * @param newLog  the content of the new supersede log
     * @param creator the creator of the new supersede log
     * @return the id of the new supersede log
     */
    @Transactional
    @CacheEvict(value = ENTRIES, allEntries = true)
    public String createNewSupersede(String entryId, EntryNewDTO newLog, PersonDTO creator) {
        // fetches the log to supersede
        Entry supersededLog =
                wrapCatch(
                        () -> entryRepository.findById(entryId),
                        -1,
                        "LogService::createNewSupersede"
                ).orElseThrow(
                        () -> EntryNotFound.entryNotFoundBuilder()
                                .errorCode(-2)
                                .errorDomain("LogService::createNewSupersede")
                                .build()
                );
        // execute some check
        assertion(
                () -> (supersededLog.getSupersededBy() == null ||
                        supersededLog.getSupersededBy().isEmpty())
                ,
                SupersedeAlreadyCreated.supersedeAlreadyCreatedBuilder()
                        .errorCode(-3)
                        .errorDomain("LogService::createNewSupersede")
                        .build()
        );

        // create supersede model
        Entry newEntryModel = toModelWithCreator(newLog, creator);
        // copy followups to the supersede entry
        newEntryModel.setFollowUps(supersededLog.getFollowUps());
        // set superseded eventAt to the original eventAt date
        newEntryModel.setEventAt(supersededLog.getEventAt());
        // create entry
        String newLogID = createNew(newEntryModel);
        // update all the reference to old entry with the new id
        updateReferences(entryId, newLogID);
        // update supersede
        supersededLog.setSupersededBy(newLogID);
        //update superseded entry
        var savedSupersede = wrapCatch(
                () -> entryRepository.save(supersededLog),
                -4,
                "LogService::createNewSupersede"
        );
        log.info("New supersede for '{}' created with id '{}' by '{}'", supersededLog.getTitle(), newLogID, savedSupersede.getLastModifiedBy());
        return newLogID;
    }

    /**
     * Update the references to the old entry with the new id
     *
     * @param entryId        the id of the entry that has been superseded
     * @param supersededById the id of the new superseded entry
     */
    private void updateReferences(String entryId, String supersededById) {
        // find al entry that reference to the original one
        List<Entry> referenceEntries = wrapCatch(
                () -> entryRepository.findAllByReferencesContainsAndSupersededByExists(entryId, false),
                -1,
                "LogService::updateReferences"
        );
        // now for each one update the reference
        referenceEntries.forEach(
                entry -> {
                    entry.getReferences().remove(entryId);
                    entry.getReferences().add(supersededById);
                    entry.setText(updateHtmlReferenceTag(entry.getText(), entryId, supersededById));
                    wrapCatch(
                            () -> entryRepository.save(entry),
                            -2,
                            "LogService::updateReferences"
                    );
                }
        );
    }

    /**
     * Update the reference in the text
     *
     * @param text           the text to update
     * @param entryId        the id to replace
     * @param supersededById the new id
     * @return the updated text
     */
    private String updateHtmlReferenceTag(String text, String entryId, String supersededById) {
        // scan document text and update the reference
        Document document = Jsoup.parseBodyFragment(text);
        Elements elements = document.select(ELOG_ENTRY_REF);
        for (Element element : elements) {
            // Get the 'id' attribute
            if (!element.hasAttr(ELOG_ENTRY_REF_ID)) continue;
            String id = element.attr(ELOG_ENTRY_REF_ID);
            // check if the found id is one that we need to change
            if (id.isEmpty() || id.compareToIgnoreCase(entryId) != 0) continue;
            // update id
            element.attr(ELOG_ENTRY_REF_ID, supersededById);
        }
        return document.body().html();
    }

    /**
     * Create a new follow-up for a specific log
     *
     * @param id     the id for the log the need to be followed
     * @param newLog the content of the new follow-up log
     * @return the id of the new follow-up log
     */
    @Transactional
    @CacheEvict(value = ENTRIES, allEntries = true)
    public String createNewFollowUp(String id, EntryNewDTO newLog, PersonDTO personDTO) {
        Entry rootLog =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::createNewFollowUp"
                ).orElseThrow(
                        () -> EntryNotFound.entryNotFoundBuilder()
                                .errorCode(-2)
                                .errorDomain("LogService::createNewFollowUp")
                                .build()
                );
        String newFollowupLogID = createNew(newLog, personDTO);
        // update supersede
        rootLog.getFollowUps().add(newFollowupLogID);
        wrapCatch(
                () -> entryRepository.save(rootLog),
                -4,
                "LogService::createNewSupersede"
        );
        log.info("New followup for '{}' created with id '{}'", rootLog.getTitle(), newFollowupLogID);
        return newFollowupLogID;
    }

    /**
     * Return all the follow-up log for a specific one
     *
     * @param id the id of the log parent of the follow-up
     * @return the list of all the followup of the specific log identified by the id
     */
    public List<EntrySummaryDTO> getAllFollowUpForALog(String id) {
        Entry rootLog =
                wrapCatch(
                        () -> entryRepository.findById(id),
                        -1,
                        "LogService::getAllFollowUpForALog"
                ).orElseThrow(
                        () -> EntryNotFound.entryNotFoundBuilder()
                                .errorCode(-2)
                                .errorDomain("LogService::getAllFollowUpForALog")
                                .build()
                );
        List<Entry> followUp =
                wrapCatch(
                        () -> entryRepository.findAllByIdIn(rootLog.getFollowUps()),
                        -1,
                        "LogService::getAllFollowUpForALog"
                );
        return followUp
                .stream()
                .map(
                        entryMapper::toSearchResult
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
     * @param lb        the logbooks ofr the current entry
     * @param summarize the summarization information
     */
    private void checkForSummarization(LogbookDTO lb, Summarizes summarize) {
        if (summarize == null) return;
        assertion(
                () -> ((lb.shifts() != null && !lb.shifts().isEmpty())),
                ControllerLogicException
                        .builder()
                        .errorCode(-1)
                        .errorMessage("The logbooks has not any shift")
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

    /**
     * Return the id of the entry from the origin id
     *
     * @param originId the origin id
     * @return the id of the entry
     */
    public String getIdFromOriginId(String originId) {
        Entry foundEntry = wrapCatch(
                () -> entryRepository.findByOriginId(originId),
                -1,
                "EntryService::getIdFromOriginId"
        ).orElseThrow(
                () -> EntryNotFound.entryNotFoundBuilderWithName()
                        .errorCode(-2)
                        .entryName(originId)
                        .errorDomain("EntryService::getIdFromOriginId")
                        .build()
        );
        return foundEntry.getId();
    }

    /**
     * Return all the referenced entries by this one identified by the id
     *
     * @param id the unique id of the source entry
     * @return the list of the referenced entries
     */
    public List<EntrySummaryDTO> getReferencesByEntryID(String id) {
        List<String> foundReferencesIds = wrapCatch(
                () -> entryRepository.findReferencesBySourceId(id),
                -1,
                "EntryService::getReferencesByEntryID"
        );
        if (foundReferencesIds == null) return emptyList();
        return foundReferencesIds.stream().map(
                refId -> wrapCatch(
                        () -> entryRepository.findById(refId),
                        -2,
                        "EntryService::getReferencesByEntryID"
                ).map(
                        entryMapper::toSearchResult
                ).orElseThrow(
                        () -> EntryNotFound.entryNotFoundBuilderWithName()
                                .errorCode(-3)
                                .entryName(refId)
                                .errorDomain("EntryService::getReferencesByEntryID")
                                .build()
                )
        ).toList();
    }
}
