package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.model.Attachment;
import org.apache.james.mime4j.dom.datetime.DateTime;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for the attachment managements
 */
public interface AttachmentRepository  extends MongoRepository<Attachment, String>, AttachmentRepositoryCustom {
    /**
     * Check if an attachment exists if the attachment cannot be deleted
     * @param id the id of the attachment
     * @return
     */
    boolean existsByIdAndCanBeDeletedIsFalse(String id);
    /**
     * Find all the attachment that are associated to a reference
     * @param referenceId the id of the reference
     * @return the list of the attachment
     */
    List<Attachment> findAllByReferenceInfo(String referenceId);

    // delete all attachment that are expired since some minutes
    void deleteByCreatedDateLessThanAndInUseIsFalse(LocalDateTime expirationTime);
}
