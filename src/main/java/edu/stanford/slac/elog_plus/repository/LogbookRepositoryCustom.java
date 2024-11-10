package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.model.Shift;
import edu.stanford.slac.elog_plus.model.Tag;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface LogbookRepositoryCustom {
    //String createNewTag(String logbookId, Tag newTag);

    /**
     * Atomically ensure the tag creation
     * @param logbookId the id of the logbooks
     * @param newTag the new tag to create
     */
    String ensureTag(String logbookId, Tag newTag) throws UnsupportedEncodingException, NoSuchAlgorithmException;

    List<Tag> getAllTagFor(String logbookId);

    boolean tagExistByName(String logbookId, String tagName);

    List<Shift> getAllShift(String logbookID);

    List<String> getAllLogbook();

    List<String> getAllLogbookIds();

    Optional<Shift> findShiftFromLocalTime(String logbookId, LocalTime localTime);

    Optional<Shift> findShiftFromLocalTimeWithLogbookName(String logbookId, LocalTime localTime);

    Optional<Shift> findShiftFromLocalTimeWithLogbookId(String logbookId, LocalTime localTime);

    Optional<Tag> getTagsByID(String tagId);
}
