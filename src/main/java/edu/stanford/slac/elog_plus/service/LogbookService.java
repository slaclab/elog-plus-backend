package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.base_mongodb_lib.repository.AuthenticationTokenRepository;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.*;
import edu.stanford.slac.ad.eed.baselib.api.v1.mapper.AuthMapper;
import edu.stanford.slac.ad.eed.baselib.auth.JWTHelper;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v1.dto.NewAuthorizationDTO;
import edu.stanford.slac.elog_plus.api.v1.mapper.LogbookMapper;
import edu.stanford.slac.elog_plus.api.v1.mapper.ShiftMapper;
import edu.stanford.slac.elog_plus.api.v1.mapper.TagMapper;
import edu.stanford.slac.elog_plus.exception.*;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.model.Shift;
import edu.stanford.slac.elog_plus.model.Tag;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import edu.stanford.slac.elog_plus.repository.LogbookRepository;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import edu.stanford.slac.elog_plus.utility.StringUtilities;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;
import static edu.stanford.slac.ad.eed.baselib.exception.Utility.wrapCatch;
import static edu.stanford.slac.elog_plus.config.CacheConfig.LOGBOOKS;
import static edu.stanford.slac.elog_plus.config.CacheConfig.TAGS;

@Log4j2
@Service
@AllArgsConstructor
public class LogbookService {
    private final TagMapper tagMapper;
    private final ShiftMapper shiftMapper;
    private final LogbookMapper logbookMapper;
    private final EntryRepository entryRepository;
    private final LogbookRepository logbookRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;
    private final AuthService authService;
    private final AuthorizationServices authorizationServices;
    private final AuthMapper authMapper;
    private final AppProperties appProperties;
    private final JWTHelper jwtHelper;

    /**
     * Validate the shift
     *
     * @return the validated shift
     */
    public List<LogbookDTO> getAllLogbook() {
        return getAllLogbook(Optional.empty());
    }

    /**
     * Return all the logbooks
     *
     * @return the lis tof all logbooks
     */
    public List<String> getAllIdsReadAll() {
        return wrapCatch(
                () -> logbookRepository.findAllByReadAllIsTrue().stream().map(Logbook::getId).toList(),
                -1,
                "LogbookService:getAllIdsReadAll"
        );
    }

    /**
     * Return all the logbooks
     *
     * @return the lis tof all logbooks
     */
    public List<String> getAllIdsWriteAll() {
        return wrapCatch(
                () -> logbookRepository.findAllByWriteAllIsTrue().stream().map(Logbook::getId).toList(),
                -1,
                "LogbookService:getAllIdsWriteAll"
        );
    }

    /**
     * Return all the logbooks
     *
     * @return the lis tof all logbooks
     */
    public List<LogbookDTO> getAllLogbook(Optional<Boolean> includeAuthorization) {
        return wrapCatch(
                () -> logbookRepository.findAll()
                        .stream()
                        .map(
                                lb -> logbookMapper.fromModel(
                                        lb,
                                        includeAuthorization.orElse(false)
                                )
                        ).collect(Collectors.toList()),
                -1,
                "LogbookService::getAllLogbook"
        );
    }

    /**
     * Get logbook summary by id
     *
     * @param logbookId the unique id of the logbook
     * @return return the summary of the logbook
     */
    public LogbookSummaryDTO getSummaryById(String logbookId) {
        return wrapCatch(
                () -> logbookRepository.findById(logbookId)
                        .map(
                                logbookMapper::fromModelToSummaryDTO
                        ).orElseThrow(
                                () -> LogbookNotFound.logbookNotFoundBuilderWitLId()
                                        .errorCode(-1)
                                        .logbookId(logbookId)
                                        .errorDomain("LogbookService::getSummaryById")
                                        .build()
                        ),
                -2,
                "LogbookService::getSummaryById"
        );
    }

    /**
     * Create a new logbooks
     *
     * @param newLogbookDTO the new logbooks
     * @return the id of the newly created logbooks
     */
    @CacheEvict(value = {LOGBOOKS, TAGS}, allEntries = true)
    public String createNew(NewLogbookDTO newLogbookDTO) {
        // normalize the name
        newLogbookDTO = newLogbookDTO.toBuilder()
                .name(StringUtilities.tagNameNormalization(newLogbookDTO.name()))
                .build();
        // check for logbooks with the same name
        NewLogbookDTO finalNewLogbookDTO = newLogbookDTO;

        assertion(
                () -> !wrapCatch(
                        () -> logbookRepository.existsByName(
                                finalNewLogbookDTO.name()
                        ),
                        -1,
                        "LogbookService::createNew"),
                LogbookAlreadyExists.logbookAlreadyExistsBuilder()
                        .errorCode(-2)
                        .errorDomain("LogbookService::createNew")
                        .build()
        );

        var toSave = logbookMapper.fromDTO(finalNewLogbookDTO);
        if (toSave.isWriteAll()) {
            // force also readAll to true
            toSave.setReadAll(true);
        }
        // create new logbook
        Logbook newLogbook = wrapCatch(
                () -> logbookRepository.save(toSave),
                -3,
                "LogbookService::createNew");
        log.info("New logbooks '{}' created", newLogbook.getName());
        return newLogbook.getId();
    }


    /**
     * Update an existing logbooks
     *
     * @param logbookDTO the updated logbooks
     */
    @Transactional
    @CacheEvict(value = {LOGBOOKS, TAGS}, allEntries = true)
    public LogbookDTO update(String logbookId, UpdateLogbookDTO logbookDTO) {
        // check if id exists
        Logbook lbToUpdated = wrapCatch(
                () -> logbookRepository.findById(logbookId),
                -1,
                "LogbookService::update"
        ).orElseThrow(
                () -> LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogbookService::update")
                        .build()
        );
        assertion(
                () -> logbookDTO.name() != null && !logbookDTO.name().isEmpty(),
                ControllerLogicException.builder()
                        .errorCode(-3)
                        .errorMessage("The name field is mandatory")
                        .errorDomain("LogbookService::update")
                        .build()
        );
        assertion(
                () -> logbookDTO.tags() != null,
                ControllerLogicException.builder()
                        .errorCode(-3)
                        .errorMessage("The tags field is mandatory")
                        .errorDomain("LogbookService::update")
                        .build()
        );
        assertion(
                () -> logbookDTO.shifts() != null,
                ControllerLogicException.builder()
                        .errorCode(-3)
                        .errorMessage("The shift field is mandatory")
                        .errorDomain("LogbookService::update")
                        .build()
        );
        Logbook updateLogbookInfo = logbookMapper.fromDTO(logbookDTO);
        if (lbToUpdated.getShifts() == null) {
            lbToUpdated.setShifts(new ArrayList<>());
        }
        if (lbToUpdated.getTags() == null) {
            lbToUpdated.setTags(new ArrayList<>());
        }

        // normalize name and shift
        lbToUpdated.setName(
                StringUtilities.logbookNameNormalization(logbookDTO.name())
        );
        // check that all shifts id are present, the shift with no id it's ok because he will be created
        verifyShiftAndUpdate(
                updateLogbookInfo.getShifts(),
                lbToUpdated.getShifts(),
                -4,
                "LogbookService:update");

        // check that all tags id are present, the tag with no id it's ok because he will be created
        verifyTagAndUpdate(
                updateLogbookInfo.getTags(),
                lbToUpdated.getTags(),
                -5,
                "LogbookService:update"
        );


        lbToUpdated.setReadAll(updateLogbookInfo.isReadAll());
        lbToUpdated.setWriteAll(updateLogbookInfo.isWriteAll());
        if (updateLogbookInfo.isWriteAll()) {
            // force also readAll to true
            lbToUpdated.setReadAll(true);
        }

        //we can save the logbooks
        var updatedLB = wrapCatch(
                () -> logbookRepository.save(lbToUpdated),
                -6,
                "LogbookService:update"
        );
        log.info("Logbook '{}' has been updated", lbToUpdated.getName());
        return logbookMapper.fromModel(
                updatedLB,
                true
        );
    }

    /**
     * verify and updates the tags
     *
     * @param updateTagList the list of the tags updates
     * @param actualTagList the list of actual tags
     */
    private void verifyTagAndUpdate(List<Tag> updateTagList, List<Tag> actualTagList, int errorCode, String errorDomain) {
        //normalize tag
        updateTagList.forEach(
                t -> t.setName(
                        StringUtilities.tagNameNormalization(
                                t.getName()
                        )
                )
        );

        for (Tag tagToUpdate :
                updateTagList) {
            if (tagToUpdate.getId() == null) {
                // generate new ID
                tagToUpdate.setId(UUID.randomUUID().toString());
                continue;
            }
            boolean exists = actualTagList.stream().anyMatch(
                    s -> s.getId().compareTo(tagToUpdate.getId()) == 0
            );
            // check if the script with the same id exists, in case fire exception
            assertion(
                    () -> exists,
                    TagNotFound.tagNotFoundBuilder()
                            .errorCode(errorCode)
                            .tagName(tagToUpdate.getName())
                            .errorDomain(errorDomain)
                            .build()
            );
        }

        //check which shift should be removed
        for (Tag t :
                actualTagList) {
            boolean willBeUpdated = updateTagList.stream().anyMatch(
                    ut -> ut.getId() != null && ut.getId().compareTo(t.getId()) == 0
            );
            if (willBeUpdated) continue;

            // in this case the shift will be deleted
            // now if we need to check if the shift is used
            long summariesForTagName = wrapCatch(
                    () -> entryRepository.countByTagsContains(t.getId()),
                    -1,
                    "LogbookService:verifyTagAndUpdate"
            );

            assertion(
                    () -> summariesForTagName == 0,
                    ControllerLogicException
                            .builder()
                            .errorCode(-2)
                            .errorMessage(String.format("The tag with the id '%s' cannot be deleted because has associated summaries", t.getId()))
                            .errorDomain("LogbookService:verifyTagAndUpdate")
                            .build()
            );
        }

        // we can update the tags
        actualTagList.clear();
        actualTagList.addAll(updateTagList);
    }

    /**
     * Update the actual list of shift with the updated shift list
     *
     * @param updatedShifts a list that contains the shift updates
     * @param actualShift   a list that contains the actual shift list
     * @param errorCode     is the error code to generate in case of fails
     * @param errorDomain   is the error domain in case of fail
     */
    private void verifyShiftAndUpdate(List<Shift> updatedShifts, List<Shift> actualShift, int errorCode, String errorDomain) {
        //normalize tag
        updatedShifts.forEach(
                s -> s.setName(
                        StringUtilities.shiftNameNormalization(
                                s.getName()
                        )
                )
        );

        // check if the shift to update exists and have a valid id
        for (Shift shiftToUpdate :
                updatedShifts) {
            if (shiftToUpdate.getId() == null) {
                // generate new ID
                shiftToUpdate.setId(UUID.randomUUID().toString());
                continue;
            }
            boolean exists = actualShift.stream().anyMatch(
                    s -> s.getId().compareTo(shiftToUpdate.getId()) == 0
            );
            // check if the script with the same id exists, in case fire exception
            assertion(
                    () -> exists,
                    ShiftNotFound.shiftNotFoundBuilder()
                            .errorCode(errorCode)
                            .shiftName(shiftToUpdate.getName())
                            .errorDomain(errorDomain)
                            .build()
            );
        }

        //check which shift should be removed
        for (Shift s :
                actualShift) {
            boolean willBeUpdated = updatedShifts.stream().anyMatch(
                    us -> us.getId() != null && us.getId().compareTo(s.getId()) == 0
            );
            if (willBeUpdated) continue;

            // in this case the shift will be deleted
            // now if we need to check if the shift is used
            long summariesForShift = wrapCatch(
                    () -> entryRepository.countBySummarizes_ShiftId(s.getId()),
                    -1,
                    "LogbookService:verifyShiftAndUpdate"
            );

            assertion(
                    () -> summariesForShift == 0,
                    ControllerLogicException
                            .builder()
                            .errorCode(-2)
                            .errorMessage(String.format("The shift with the id '%s' cannot be deleted because has associated summaries", s.getId()))
                            .errorDomain("LogbookService:verifyShiftAndUpdate")
                            .build()
            );
        }

        actualShift.clear();
        for (Shift shift :
                updatedShifts) {
            Shift shiftToAdd = validateShift(
                    shift,
                    errorCode,
                    errorDomain
            );
            checkShiftAmongAllOther(shiftToAdd, actualShift, errorCode, errorDomain);

            if (shiftToAdd.getId() == null) {
                shiftToAdd.setId(UUID.randomUUID().toString());
            }
            actualShift.add(shiftToAdd);
        }
    }

    /**
     * Return the full logbooks description
     *
     * @param logbookId the logbook id
     * @return the full logbooks
     */
    @Cacheable(value = LOGBOOKS, key = "'getLogbook-' + #logbookId")
    public LogbookDTO getLogbook(String logbookId) {
        return getLogbook(logbookId, Optional.empty());
    }

    /**
     * Return the full logbooks description
     *
     * @param logbookIds the logbooks id
     * @return the full logbooks list
     */
    public List<LogbookDTO> getLogbook(List<String> logbookIds, Optional<Boolean> includeAuthorizations) {
        return wrapCatch(() -> logbookRepository.findAllById(
                        logbookIds
                ).stream().map(
                        lb -> logbookMapper.fromModel(
                                lb,
                                includeAuthorizations.orElse(false)
                        )
                ).toList(),
                -1,
                "LogbookService:getLogbook"
        );
    }

    /**
     * Return the full logbooks description
     *
     * @param logbookId the logbooks id
     * @return the full logbooks
     */
    @Cacheable(value = LOGBOOKS, key = "'getLogbook-' + #logbookId +'_'+ #includeAuthorizations.orElse(false)")
    public LogbookDTO getLogbook(String logbookId, Optional<Boolean> includeAuthorizations) {
        return wrapCatch(
                () -> logbookRepository.findById(
                        logbookId
                ).map(
                        lb -> logbookMapper.fromModel(
                                lb,
                                includeAuthorizations.orElse(false)
                        )
                ).orElseThrow(
                        () -> LogbookNotFound.logbookNotFoundBuilder()
                                .errorCode(-2)
                                .errorDomain("LogbookService:getLogbook")
                                .build()
                ),
                -1,
                "LogbookService:getLogbook"
        );
    }

    /**
     * Return a full log identified by its name
     *
     * @param logbookName the name of the logbooks
     * @return the full logbooks
     */
    public LogbookDTO getLogbookByName(@NotNull String logbookName) {
        Optional<Logbook> lb = wrapCatch(
                () -> logbookRepository.findByName(logbookName.toLowerCase()),
                -1,
                "LogbookService:getLogbookByName"
        );
        return logbookMapper.fromModel(
                lb.orElseThrow(
                        () -> LogbookNotFound.logbookNotFoundBuilder()
                                .errorCode(-1)
                                .errorDomain("LogbookService:getLogbookByName")
                                .build()
                )
        );

    }

    /**
     * Check if a logbooks with a specific name exists
     *
     * @param logbookName the name of the logbooks to check
     * @return true if the logbooks exists
     */
    public Boolean existByName(@NotNull String logbookName) {
        return wrapCatch(
                () -> logbookRepository.existsByName(
                        logbookName.toLowerCase()
                ),
                -1,
                "LogbookService::existByName");
    }

    /**
     * Check if a logbooks with a specific id exists
     *
     * @param logbookId the id of the logbooks to check
     * @return true if the logbooks exists
     */
    public Boolean existById(String logbookId) {
        return wrapCatch(
                () -> logbookRepository.existsById(
                        logbookId
                ),
                -1,
                "LogbookService::existById");
    }

    /**
     * Create new tag for the logbooks
     *
     * @param logbookId the logbooks id
     * @param newTagDTO the new tag description
     * @return the id of the newly crated tag
     */
    public String createNewTag(String logbookId, NewTagDTO newTagDTO) {
        assertOnLogbook(logbookId, -1, "LogbookService:createNewTag");
        // normalize the name
        newTagDTO = newTagDTO.toBuilder()
                .name(StringUtilities.tagNameNormalization(newTagDTO.name()))
                .build();
        NewTagDTO finalNewTagDTO = newTagDTO;

        boolean exists = wrapCatch(
                () -> logbookRepository.tagExistByName(
                        logbookId,
                        finalNewTagDTO.name()
                ),
                -1,
                "LogbookService:createNewTag"
        );
        assertion(
                () -> !exists,
                TagAlreadyExists.tagAlreadyExistsBuilder()
                        .errorCode(-2)
                        .tagName(finalNewTagDTO.name())
                        .errorDomain("LogbookService::createNewTag")
                        .build()
        );
        return wrapCatch(
                () -> logbookRepository.ensureTag(
                        logbookId,
                        tagMapper.fromDTO(finalNewTagDTO)
                ),
                -3,
                "LogbookService:createNewTag"
        );
    }


    /**
     * Create new tag just in case it doesn't exist
     *
     * @param logbookId the logbooks id
     * @param tagName   the tag name
     */
    public String ensureTag(String logbookId, String tagName) {
        assertOnLogbook(logbookId, -1, "LogbookService:ensureTag");
        return wrapCatch(
                () ->
                        logbookRepository.ensureTag(
                                logbookId,
                                tagMapper.fromDTO(
                                        NewTagDTO
                                                .builder()
                                                .name(StringUtilities.tagNameNormalization(tagName))
                                                .build()
                                )
                        )
                ,
                -2,
                "LogbookService:ensureTag"
        );
    }

    /**
     * Check if a tag exist for the log
     * <p>
     * the name is normalized before checking
     *
     * @param logbookId the id of the logbooks
     * @param tagName   the name of the tag
     * @return true if the tag exists
     */
    @Cacheable(value = TAGS, key = "'tagExistForLogbook-' + #logbookId + '_' + #tagName")
    public Boolean tagExistForLogbook(String logbookId, String tagName) {
        return wrapCatch(
                () -> logbookRepository.tagExistByName(
                        logbookId,
                        StringUtilities.tagNameNormalization(
                                tagName
                        )
                ),
                -1,
                "LogbookService:tagExistForLogbook"
        );
    }

    /**
     * Return all tags ofr a logbooks
     *
     * @param logbookId the id of the logbooks
     * @return all the logbooks tags
     */
    public List<TagDTO> getAllTags(String logbookId) {
        assertOnLogbook(logbookId, -1, "LogbookService:getAllTags");
        List<Tag> allTag = wrapCatch(
                () -> logbookRepository.getAllTagFor(logbookId),
                -2,
                "LogbookService:getAllTags"
        );
        return allTag.stream()
                .map(
                        tagMapper::fromModel
                ).
                collect(Collectors.toList());
    }

    /**
     * Return the logbook summary where the tag belong
     *
     * @param tagId the unique tag id
     * @return the logbook summary which the tag belong
     */
    public LogbookSummaryDTO getLogbookSummaryForTagId(String tagId) {
        Optional<Logbook> logbook = wrapCatch(
                () -> logbookRepository.findByTagsIdIs(tagId),
                -1,
                "LogbookService:getLogbookSummaryForTagId"
        );
        return logbook.map(
                logbookMapper::fromModelToSummaryDTO
        ).orElseThrow(
                () -> LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("")
                        .build()
        );
    }

    private void assertOnLogbook(String logbookId, Integer error, String domain) {
        boolean logbook = wrapCatch(
                () -> logbookRepository.existsById(logbookId),
                -1,
                "LogbookService:assertOnLogbook"
        );
        assertion(
                () -> logbook,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(error)
                        .errorDomain(domain)
                        .build()
        );
    }

    public List<TagDTO> getAllTagsByLogbooksName(List<String> logbooks) {
        List<TagDTO> result = new ArrayList<>();

        if (logbooks == null || logbooks.isEmpty()) {
            logbooks = wrapCatch(
                    logbookRepository::getAllLogbook,
                    -1,
                    "LogbookService:getAllTagsByLogbooksName"
            );
        }

        for (String logbookName :
                logbooks) {
            if (!existByName(logbookName)) {
                continue;
            }
            LogbookDTO lbDTO = getLogbookByName(logbookName);
            result.removeAll(lbDTO.tags());
            result.addAll(lbDTO.tags());
        }
        return result;
    }

    /**
     * Return all tags for the logbooks
     *
     * @param logbookIds the ids of the logbooks
     * @return all the tags
     */
    public List<TagDTO> getAllTagsByLogbooksIds(List<String> logbookIds) {
        List<TagDTO> result = new ArrayList<>();

        if (logbookIds == null || logbookIds.isEmpty()) {
            logbookIds = wrapCatch(
                    logbookRepository::getAllLogbookIds,
                    -1,
                    "LogbookService:getAllTagsByLogbooksName"
            );
        }

        for (String id :
                logbookIds) {
            if (!existById(id)) {
                continue;
            }
            LogbookDTO lbDTO = getLogbook(id);
            result.removeAll(lbDTO.tags());
            result.addAll(lbDTO.tags());
        }
        return result;
    }

    /**
     * Replace the shift in this way
     * <p>
     * if a shift as no id it will be created as new, if it has an ID,
     * it will be used for find it within all the shift, in case is not found an exception will be thrown
     *
     * @param logbookId   the logbooks id
     * @param allNewShift all the new shift
     */
    @Transactional()
    @CacheEvict(value = {LOGBOOKS, TAGS}, allEntries = true)
    public void replaceShift(String logbookId, List<ShiftDTO> allNewShift) {
        Optional<Logbook> lb =
                wrapCatch(
                        () -> logbookRepository.findById(
                                logbookId
                        ),
                        -1,
                        "LogbookService:replaceShift"
                );
        assertion(
                lb::isPresent,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogbookService:replaceShift")
                        .build()
        );

        Logbook lbToSave = lb.get();

        // verify and update the shifts
        verifyShiftAndUpdate(
                shiftMapper.fromDTO(allNewShift),
                lbToSave.getShifts(),
                -3,
                "LogbookService:replaceShift"
        );

        wrapCatch(
                () -> logbookRepository.save(
                        lbToSave
                ),
                -3,
                "LogbookService:addShift"
        );
    }

    /**
     * Add new shift checking for the correctness
     *
     * @param logbookId   the id of the logbooks from which we want to add the shift
     * @param newShiftDTO the shift description
     */
    @Transactional(propagation = Propagation.NESTED)
    @CacheEvict(value = {LOGBOOKS, TAGS}, allEntries = true)
    public String addShift(String logbookId, NewShiftDTO newShiftDTO) {
        // validate the shift
        Shift shiftToAdd = validateShift(shiftMapper.fromDTO(newShiftDTO), -1, "LogbookService:addShift");

        // normalize shift name
        shiftToAdd.setName(
                StringUtilities.shiftNameNormalization(
                        shiftToAdd.getName()
                )
        );

        Optional<Logbook> lb =
                wrapCatch(
                        () -> logbookRepository.findById(
                                logbookId
                        ),
                        -3,
                        "LogbookService:addShift"
                );
        assertion(
                lb::isPresent,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogbookService:addShift")
                        .build()
        );

        Logbook lbToSave = lb.get();

        //create ID
        shiftToAdd.setId(UUID.randomUUID().toString());

        // get all shift already saved
        List<Shift> allShift = lbToSave.getShifts();

        // check if the new shift is not overlapped with the other
        checkShiftAmongAllOther(
                shiftToAdd,
                allShift,
                -3,
                "LogbookService:addShift");

        // we can add the shift
        lbToSave.getShifts().add(shiftToAdd);

        // save shift
        wrapCatch(
                () -> logbookRepository.save(
                        lbToSave
                ),
                -3,
                "LogbookService:addShift"
        );
        return shiftToAdd.getId();
    }

    @Transactional(propagation = Propagation.NESTED)
    public void updateShift(String logbookId, ShiftDTO shiftDTO) {
        // validate the shift
        Shift shiftToUpdate = validateShift(
                shiftMapper.fromDTO(shiftDTO),
                -1,
                "LogbookService:updateShift"
        );

        // normalize shift name
        shiftToUpdate.setName(
                StringUtilities.shiftNameNormalization(
                        shiftToUpdate.getName()
                )
        );

        Optional<Logbook> lb =
                wrapCatch(
                        () -> logbookRepository.findById(
                                logbookId
                        ),
                        -3,
                        "LogbookService:addShift"
                );
        assertion(
                lb::isPresent,
                LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-2)
                        .errorDomain("LogbookService:addShift")
                        .build()
        );

        Logbook lbToSave = lb.get();

        //create ID
        shiftToUpdate.setId(UUID.randomUUID().toString());

        // get all shift already saved
        List<Shift> allShift = lbToSave.getShifts()
                .stream().filter(
                        s -> s.getId().compareTo(shiftToUpdate.getId()) == 0
                ).collect(Collectors.toList());


        // check if the new shift is not overlapped with the other
        checkShiftAmongAllOther(
                shiftToUpdate,
                allShift,
                -3,
                "LogbookService:addShift");

        // we can add the shift
        lbToSave.getShifts().add(shiftToUpdate);

        // save shift
        wrapCatch(
                () -> logbookRepository.save(
                        lbToSave
                ),
                -3,
                "LogbookService:addShift"
        );
    }

    /**
     * Return the shift which the date fall in its range
     *
     * @param logbookId the logbooks unique identifier
     * @param localTime the time of the event in the day
     * @return the found shift, if eny matches
     */
    public Optional<LogbookShiftDTO> findShiftByLocalTime(String logbookId, LocalTime localTime) {
        LogbookDTO logbook = getLogbook(logbookId);
        // //remove the seconds and nanoseconds to input date
        localTime = localTime.withSecond(0).withNano(0);
        LocalTime finalLocalTime = localTime;
        return logbook.shifts().stream().filter(
                s -> {
                    var fromDate = DateUtilities.fromUTCString(s.from());
                    var toDate = DateUtilities.fromUTCString(s.to());
                    return DateUtilities.isBetween(
                            fromDate,
                            toDate,
                            finalLocalTime
                    );
                }
        ).findAny().map(
                s -> LogbookShiftDTO.builder()
                        .id(s.id())
                        .name(s.name())
                        .from(s.from())
                        .to(s.to())
                        .logbook(
                                logbookMapper.fromModelToSummaryDTO(logbook)
                        )
                        .build()
        );
    }

    /**
     * Check if the tad id exists in any of logbooks names
     *
     * @param tagId      the id of the tag to find
     * @param logbookIds the logbooks where search the id
     * @return true if the tag exists
     */
    public boolean tagIdExistInAnyLogbookIds(String tagId, List<String> logbookIds) {
        return wrapCatch(
                () -> logbookRepository.existsByIdInAndTagsIdIs(
                        logbookIds,
                        tagId
                ),
                -1,
                "LogbookService:tagIdExistInAnyLogbooksNames"
        );
    }

    public Optional<TagDTO> getTagById(String tagId) {
        Optional<Tag> tag = wrapCatch(
                () -> logbookRepository.getTagsByID(
                        tagId
                ),
                -1,
                "LogbookService:getTagById"
        );
        return tag.map(
                tagMapper::fromModel
        );
    }

    /**
     * Add a new authentication token to the logbook
     *
     * @param id                        the logbook id
     * @param newAuthenticationTokenDTO is the new token information
     */
    @Transactional
    public boolean addNewAuthenticationToken(String id, NewAuthenticationTokenDTO newAuthenticationTokenDTO) {
        final Logbook lb = wrapCatch(
                () -> logbookRepository.findById(id),
                -1,
                "LogbookService:addNewAuthenticationToken"
        ).orElseThrow(
                () -> LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-1)
                        .errorDomain("LogbookService:addNewAuthenticationToken")
                        .build()
        );

        assertion(
                () -> authenticationTokenRepository.findAllByEmailEndsWith(
                                "%s.%s".formatted(
                                        lb.getName(),
                                        appProperties.getAppEmailPostfix()
                                )
                        )
                        .stream()
                        .filter(
                                (t) -> t.getName().compareToIgnoreCase(newAuthenticationTokenDTO.name()) == 0
                        )
                        .findAny().isEmpty(),
                ControllerLogicException
                        .builder()
                        .errorCode(-2)
                        .errorMessage("A token with the same name already exists")
                        .errorDomain("LogbookService:addNewAuthenticationToken")
                        .build()
        );
        AuthenticationToken authTok = authMapper.toModelApplicationToken(newAuthenticationTokenDTO, lb.getName());
        authTok = authTok.toBuilder()
                .token(
                        jwtHelper.generateAuthenticationToken(
                                authTok
                        )
                )
                .build();
        AuthenticationToken finalAuthTok = authTok;
        wrapCatch(
                () -> authenticationTokenRepository.save(finalAuthTok),
                -3,
                "LogbookService:addNewAuthenticationToken"
        );
        // save
        wrapCatch(
                () -> logbookRepository.save(lb),
                -4,
                "LogbookService:addNewAuthenticationToken"
        );
        return true;
    }

    /**
     * Return from a determinate logbook the token with a specific name
     *
     * @param id   the logbook id
     * @param name the name of the token to return
     * @return the found authentication token
     */
    public Optional<AuthenticationTokenDTO> getAuthenticationTokenByName(String id, String name) {
        final Logbook lb = wrapCatch(
                () -> logbookRepository.findById(id),
                -1,
                "LogbookService:getAuthenticationTokenByName"
        ).orElseThrow(
                () -> LogbookNotFound.logbookNotFoundBuilder()
                        .errorCode(-1)
                        .errorDomain("LogbookService:getAuthenticationTokenByName")
                        .build()
        );
        // find, translate and return
        return wrapCatch(
                () -> authenticationTokenRepository.findByNameIsAndEmailEndsWith(
                        name,
                        "%s.%s".formatted(lb.getName(), appProperties.getAppEmailPostfix())),
                -2,
                "LogbookService:getAuthenticationTokenByName"
        ).map(
                authMapper::toTokenDTO
        );
    }

    /**
     * Ensure that a list of user hase the authorization on a list of logbooks
     *
     * @param logbookNames the list of logbooks names
     * @param userIds      the list of user ids
     */
    @Transactional
    public void ensureAuthorizationOnLogbook(
            List<String> logbookNames,
            List<String> userIds,
            AuthorizationTypeDTO authorizationType
    ) {
        // remove logbooks duplicates
        logbookNames = logbookNames.stream().distinct().collect(Collectors.toList());
        // remove duplicates
        userIds = userIds.stream().distinct().collect(Collectors.toList());

        // apply the authorization on the logbooks
        for (String logbookName :
                logbookNames) {
            var fullLogbook = getLogbookByName(logbookName);
            List<NewAuthorizationDTO> newAuth = new ArrayList<>();
            for (String userId :
                    userIds) {
                String logbookId = fullLogbook.id();
                if (fullLogbook.authorizations() == null) {
                    // ensure authorization lists
                    fullLogbook = fullLogbook.toBuilder().authorizations(new ArrayList<>()).build();
                }
                // check if the user has an authorization compatible with the authorization type
                fullLogbook.authorizations().stream().filter(
                        a -> a.ownerId().compareToIgnoreCase(userId) == 0
                ).filter(
                        a -> a.permission().compareTo(authorizationType) == 0
                ).findAny().ifPresentOrElse(
                        (a) -> {
                            log.info("User '{}' already has the authorization '{}' on logbook '{}'", userId, authorizationType, logbookName);
                        },
                        () -> {
                            // create the authorization
                            newAuth.add(
                                    NewAuthorizationDTO.builder()
                                            .ownerId(userId)
                                            .ownerType(AuthorizationOwnerTypeDTO.User)
                                            .permission(authorizationType)
                                            .resourceId(logbookId)
                                            .resourceType(ResourceTypeDTO.Logbook)
                                            .build()
                            );
                        }
                );
            }

            // update the logbook
            if (!newAuth.isEmpty()) {
                // create all new authorization
                for (NewAuthorizationDTO auth :
                        newAuth) {
                    wrapCatch(
                            () -> {
                                authorizationServices.createNew(
                                        auth
                                );
                                return null;
                            },
                            -1,
                            "LogbookService:ensureAuthorizationOnLogbook"
                    );
                }
            }
        }
    }


    /**
     * Delete the authorization for the logbook
     *
     * @param logbookId the logbook id
     */
    public void deleteUsersLogbookAuthorization(String logbookId) {
        wrapCatch(
                () -> {
                    authService.deleteAuthorizationForResourcePrefix(
                            "/logbook/%s".formatted(logbookId),
                            AuthorizationOwnerTypeDTO.User
                    );
                    return null;
                },
                -1,
                "LogbookService:deleteLogbookAuthorization"
        );
    }

    /**
     * Delete the authorization for the logbook
     *
     * @param logbookId the logbook id
     */
    public void deleteGroupsLogbookAuthorization(String logbookId) {
        wrapCatch(
                () -> {
                    authService.deleteAuthorizationForResourcePrefix(
                            "/logbook/%s".formatted(logbookId),
                            AuthorizationOwnerTypeDTO.Group
                    );
                    return null;
                },
                -1,
                "LogbookService:deleteLogbookAuthorization"
        );
    }

    /**
     * Delete the authorization for the logbook and an user
     *
     * @param logbookId the logbook id
     * @param userId    the user id
     */
    public void deleteLogbookUsersAuthorization(String logbookId, String userId) {
        wrapCatch(
                () -> {
                    authService.deleteAuthorizationForResourcePrefix(
                            "/logbook/%s".formatted(logbookId),
                            userId,
                            AuthorizationOwnerTypeDTO.User
                    );
                    return null;
                },
                -1,
                "LogbookService:deleteLogbookAuthorization"
        );
    }

    /**
     * Delete the authorization for the logbook
     *
     * @param logbookId the logbook id
     * @param groupId   the group id
     */
    public void deleteLogbookGroupAuthorization(String logbookId, String groupId) {
        wrapCatch(
                () -> {
                    authService.deleteAuthorizationForResourcePrefix(
                            "/logbook/%s".formatted(logbookId),
                            groupId,
                            AuthorizationOwnerTypeDTO.Group
                    );
                    return null;
                },
                -1,
                "LogbookService:deleteLogbookAuthorization"
        );
    }

    /**
     * Apply the authorization
     * will remove all the authorization for the logbook and then apply the new one and only the highest
     * authorization for each user will be considered
     *
     * @param authByLogbook the list of authorizations to apply for each logbook (the key)
     */
    private void applyDistinctAuthorization(Map<String, List<AuthorizationDTO>> authByLogbook, AuthorizationOwnerTypeDTO ownerType) {
        Map<AuthorizationTypeDTO, Integer> priorities = Map.of(
                AuthorizationTypeDTO.Admin, 1,
                AuthorizationTypeDTO.Write, 2,
                AuthorizationTypeDTO.Read, 3
        );

        // Transform the entries in authByLogbook
        Map<String, List<AuthorizationDTO>> highestAuthPerUserPerLogbook = authByLogbook
                .entrySet()
                .stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey, // Corrected method to access key
                                entry -> entry.getValue().stream()
                                        .collect(
                                                Collectors.groupingBy(
                                                        AuthorizationDTO::owner, // Assume getOwner() method returns userId
                                                        Collectors.reducing(
                                                                (auth1, auth2) -> {
                                                                    // Select the highest authorization based on priority
                                                                    return priorities.get(auth1.authorizationType()) > priorities.get(auth2.authorizationType()) ? auth2 : auth1;
                                                                }
                                                        )
                                                ))
                                        .values() // Collection of Optional<AuthorizationDTO>
                                        .stream()
                                        .map(Optional::get) // Extract from Optional, ensure no null values
                                        .collect(Collectors.toList()) // Collect into List
                        )
                );

        highestAuthPerUserPerLogbook.forEach(
                (logbookId, authList) -> {
                    // clear all authorization for the logbook
                    authService.deleteAuthorizationForResourcePrefix("/logbook/%s".formatted(logbookId), ownerType);

                    authList.forEach(
                            auth -> {
                                // create the authorization
                                wrapCatch(
                                        () -> authService.ensureAuthorization(auth),
                                        -1,
                                        "LogbookService:updateAuthorizations"
                                );
                            }
                    );
                }
        );
    }


    /**
     * Check if the shift overlap with the other
     *
     * @param newShift    the new shift to check
     * @param allShift    all the shift to check
     * @param errorCode   the error code to generate in case of fail
     * @param errorDomain the error domain in case of fail
     */
    private void checkShiftAmongAllOther(
            Shift newShift,
            List<Shift> allShift,
            Integer errorCode,
            String errorDomain) {
        for (Shift savedShift :
                allShift) {
            // check from field
            if (overlap(
                    newShift.getFromTime(),
                    newShift.getToTime(),
                    savedShift.getFromTime(),
                    savedShift.getToTime()
            )) {
                throw ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage(String.format("New shift %s overlap the shift %s", newShift.getName(), savedShift.getName()))
                        .errorDomain(errorDomain)
                        .build();
            }
        }
    }

    /**
     * Check if two interval overlap
     *
     * @param start1 the start of the first interval
     * @param end1   the end of the first interval
     * @param start2 the start of the second interval
     * @param end2   the end of the second interval
     * @return true if the interval overlap
     */
    private boolean overlap(LocalTime start1, LocalTime end1, LocalTime start2, LocalTime end2) {
        if (start1.isAfter(end1)) { // interval 1 crosses midnight
            if (start2.isAfter(end2)) { // both intervals cross midnight, so they overlap at midnight
                return true;
            }
            // Swap the intervals so interval 1 does not cross midnight
            return overlap(start2, end2, start1, end1);
        }

        // Now we know that interval 1 cannot cross midnight
        if (start2.isAfter(end2)) { // Interval 2 crosses midnight
            return start2.isBefore(end1) || end2.isAfter(start1);
        } else { // None of the intervals crosses midnight
            return start2.isBefore(end1) && end2.isAfter(start1);
        }
    }

    /**
     * Validate the shift
     *
     * @param shiftToAdd is the shift to validate
     */
    private Shift validateShift(Shift shiftToAdd, Integer errorCode, String errorDomain) {
        final String timeRegex = "^(0?[0-9]|1[0-9]|2[0-3]):([0-5][0-9])$";
        final Pattern pattern = Pattern.compile(timeRegex, Pattern.MULTILINE);

        assertion(
                () -> shiftToAdd != null,
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift cannot be null")
                        .errorDomain(errorDomain)
                        .build()
        );

        // check 'name'
        assertion(
                () -> shiftToAdd.getName() != null && !shiftToAdd.getName().isEmpty(),
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift 'name' is mandatory")
                        .errorDomain(errorDomain)
                        .build()
        );

        //check 'from' field
        assertion(
                () -> shiftToAdd.getFrom() != null && !shiftToAdd.getFrom().isEmpty(),
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift 'from' field is mandatory")
                        .errorDomain(errorDomain)
                        .build()
        );
        Matcher fromMatcher = pattern.matcher(shiftToAdd.getFrom());
        assertion(
                () -> fromMatcher.matches() && fromMatcher.groupCount() == 2,
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift 'from' field need to be a time in the range 00:01-23:59")
                        .errorDomain(errorDomain)
                        .build()
        );

        //check 'to' field
        assertion(
                () -> shiftToAdd.getTo() != null && !shiftToAdd.getTo().isEmpty(),
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift 'to' field is mandatory")
                        .errorDomain(errorDomain)
                        .build()
        );
        Matcher toMatcher = pattern.matcher(shiftToAdd.getTo());
        assertion(
                () -> toMatcher.matches() && toMatcher.groupCount() == 2,
                ControllerLogicException
                        .builder()
                        .errorCode(errorCode)
                        .errorMessage("The shift 'to' field need to be a time in the range 00:01-23:59")
                        .errorDomain(errorDomain)
                        .build()
        );
        shiftToAdd.setFromMinutesSinceMidnight(
                shiftToAdd.getFromTime().getHour() * 60 + shiftToAdd.getFromTime().getMinute()
        );
        shiftToAdd.setToMinutesSinceMidnight(
                shiftToAdd.getToTime().getHour() * 60 + shiftToAdd.getToTime().getMinute()
        );
        return shiftToAdd;
    }

    /**
     * Find the earliest shift among the last n shifts
     *
     * @param n        the number of shifts to consider
     * @param logbooks the logbooks to consider
     * @param fromDate the date from which to consider the shifts
     * @return the earliest date
     */
    public LocalDateTime findEarliestNShiftForLogbooks(Integer n, List<String> logbooks, LocalDateTime fromDate) {
        List<Logbook> allLogbooks = new ArrayList<>();
        if (logbooks == null || logbooks.isEmpty()) {
            allLogbooks.addAll(
                    wrapCatch(
                            logbookRepository::findAll,
                            -1,
                            "LogbookService:findEarliestNShiftForLogbooks"
                    )
            );
        } else {
            allLogbooks.addAll(
                    wrapCatch(
                            () -> logbookRepository.findAllById(logbooks),
                            -1,
                            "LogbookService:findEarliestNShiftForLogbooks"
                    )
            );
        }
        // now we need to find the earliest date among the last n shifts
        LocalDateTime earliestStart = fromDate;
        // in ths case for each logbook we need to calculate the last n shifts, each shift is a hour range
        // so we need to calculate the time range for the last n shifts
        for (Logbook logbook : allLogbooks) {
            var shifts = logbook.getShifts();
            if (shifts.isEmpty()) continue;
            // calculate the start date and time of the last n shifts
            var dateFound = findEarliestDateAmongLastNShifts(shifts, n, fromDate);
            if (dateFound.isBefore(earliestStart)) {
                earliestStart = dateFound;
            }
        }

        return earliestStart;
    }

    /**
     * Finds the earliest start date and time among the last N shifts starting from now.
     *
     * @param shifts   List of shifts with 'from' and 'to' times in HH:mm format
     * @param N        Number of last shifts to consider
     * @param fromDate The date and time from which to consider the shifts
     * @return The earliest LocalDateTime among the last N shifts
     */
    private LocalDateTime findEarliestDateAmongLastNShifts(List<Shift> shifts, int N, LocalDateTime fromDate) {
        // Sort shifts by their start time
        shifts.sort(Comparator.comparing(Shift::getFromTime));

        List<LocalDateTime> shiftStartDateTimes = new ArrayList<>();
        LocalDateTime currentDateTime = fromDate;

        while (shiftStartDateTimes.size() < N) {
            List<LocalDateTime> tempShiftStartDateTimes = new ArrayList<>();
            for (Shift shift : shifts) {
                LocalTime shiftFromTime = shift.getFromTime();
                LocalTime shiftToTime = shift.getToTime();

                boolean crossesMidnight = shiftFromTime.isAfter(shiftToTime);

                LocalDate shiftStartDate;

                // Determine the date when the shift started
                if (crossesMidnight) {
                    // Shift crosses midnight
                    if (!currentDateTime.toLocalTime().isBefore(shiftFromTime)) {
                        // Shift started today
                        shiftStartDate = currentDateTime.toLocalDate();
                    } else {
                        // Shift started yesterday
                        shiftStartDate = currentDateTime.toLocalDate().minusDays(1);
                    }
                } else {
                    // Shift does not cross midnight
                    if (!currentDateTime.toLocalTime().isBefore(shiftFromTime)) {
                        // Shift started today
                        shiftStartDate = currentDateTime.toLocalDate();
                    } else {
                        // Shift started yesterday
                        shiftStartDate = currentDateTime.toLocalDate().minusDays(1);
                    }
                }

                // Combine the date and time to get the shift start datetime
                LocalDateTime shiftStartDateTime = LocalDateTime.of(shiftStartDate, shiftFromTime);

                // Ensure that the shift started before the current date and time
                if (shiftStartDateTime.isBefore(currentDateTime)) {
                    tempShiftStartDateTimes.add(shiftStartDateTime);
                }
            }

            if (tempShiftStartDateTimes.isEmpty()) {
                // No valid shifts found for the current date, move back one day
                currentDateTime = currentDateTime.minusDays(1);
                continue;
            }

            // Find the latest shift start time before currentDateTime
            LocalDateTime latestShiftStartDateTime = Collections.max(tempShiftStartDateTimes);
            shiftStartDateTimes.add(latestShiftStartDateTime);

            // Update currentDateTime to just before the found shift to prevent duplicates
            currentDateTime = latestShiftStartDateTime.minusSeconds(1);
        }

        // After collecting the last N shift start times, find the earliest date
        return Collections.min(shiftStartDateTimes);
    }
}
