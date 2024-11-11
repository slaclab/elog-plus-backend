package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.model.Entry;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EntryRepository extends MongoRepository<Entry, String>, EntryRepositoryCustom {
    List<Entry> findAllByIdIn(List<String> ids);

    /**
     * Return the entry that is a superseded by the one identified by id
     * @param id the id of the entry
     * @return the entries that are associated to the logbook
     */
    @Cacheable(value = "entries", key = "'returnSupersededBy_'+#id")
    Optional<Entry> findBySupersededBy(String id);

    /**
     * Return the log that the one identified by id is his followUps
     * @param id the id of the followup record
     * @return the following up record
     */
    @Cacheable(value = "entries", key = "'returnFollowedBy_'+#id")
    Optional<Entry> findByFollowUpsContainsAndSupersededByIsNull(String id);

    /**
     * Return the summary associated to the shift and date
     * @param summarizesShiftId the shift name
     * @param summarizesDate the date
     * @return the found summary, if any
     */
    @Query(fields = "{'summarizes':1}")
    Optional<Entry> findBySummarizes_ShiftIdAndSummarizes_Date(String summarizesShiftId, LocalDate summarizesDate);

    /**
     * Return the entries that refer to another entry
     * @param referencedEntryId the id of the referenced entry
     * @param exists if false take in consideration only the last superseded entry
     * @return the entries that are associated to the logbook
     */
    @Cacheable(value = "entries", key = "'findAllByreferenced'+#referencedEntryId + '_' + #exists")
    List<Entry> findAllByReferencesContainsAndSupersededByExists(String referencedEntryId, Boolean exists);

    /**
     * Return the number of the summary associated to a shift
     * @param summarizesShiftId the id of the shift
     * @return the number of summaries associated to the shift
     */
    long countBySummarizes_ShiftId(String summarizesShiftId);

    /**
     * Return the number of entries that are associated to a specific tag
     * @param tagName is the tag name
     * @return the number of entries that are associated to the tag
     */
    long countByTagsContains(String tagName);

    /**
     * Check if an entry exists using and origin id
     * @param originId the id from the original system
     * @return true if the entry exists
     */
    boolean existsByOriginId(String originId);

    /**
     * Find an entry using origin id
     * @param originId the id from the original system
     * @return the entry associated with the origin id
     */
    Optional<Entry> findByOriginId(String originId);

    /**
     * Return all the entry that refer to the attachment
     * @param attachmentId the attachment id
     * @return all the entries that refer to the attachment
     */
    List<Entry> findAllByAttachmentsContains(String attachmentId);

    /**
     * Check if an entry exists using an attachment
     * @param attachmentId the attachment id
     * @return true if the entry exists
     */
    boolean existsByAttachmentsContains(String attachmentId);
}
