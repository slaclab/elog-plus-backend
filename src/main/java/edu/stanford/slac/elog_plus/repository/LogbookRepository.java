package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.elog_plus.model.Logbook;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the logbooks managements
 */
public interface LogbookRepository extends MongoRepository<Logbook, String>, LogbookRepositoryCustom{


    @NotNull
    @Override
    @Cacheable(value = "logbooks", key = "#id")
    Optional<Logbook> findById(@NotNull String id);

    /**
     * Check if a logbook exists by its id
     *
     * @param id the id of the logbook
     * @return true if the logbook exists, false otherwise
     */
    @Cacheable(value = "logbooks", key = "'exists_by_id_'+#id")
    boolean existsById(String id);

    /**
     * Check if a logbook exists by its name
     *
     * @param name the name of the logbook
     * @return true if the logbook exists, false otherwise
     */
    @Cacheable(value = "logbooks", key = "'exists_by_name'+#name")
    boolean existsByName(String name);

    /**
     * Find a logbook by its name
     *
     * @param logbookName the name of the logbook
     * @return the logbook if it exists
     */
    @Cacheable(value = "logbooks", key = "'find_by_name'+#logbookName")
    Optional<Logbook> findByName(String logbookName);

    /**
     * Check if a logbook exists by its id and tags id
     *
     * @param logbookIds the ids of the logbook
     * @return the logbook if it exists
     */
    @Cacheable(value = "logbooks", key = "'t_id_'+#tagId + '_logbooks_' + T(java.lang.String).join(',', #logbookIds)")
    boolean existsByIdInAndTagsIdIs(List<String> logbookIds, String tagId);

    /**
     * Find a logbook by tag by id
     *
     * @param tagId the ids of the logbook
     * @return the logbook if it exists
     */
    @Cacheable(value = "logbooks", key = "'by_tag_id'+#tagId")
    Optional<Logbook> findByTagsIdIs(String tagId);

    /**
     * Find all logbook ids where readAll is true
     *
     * @return the list of logbook ids
     */
    @Query(fields = "{id: 1}")
    List<Logbook> findAllByReadAllIsTrue();

    /**
     * Find all logbook ids where writeAll is true
     *
     * @return the list of logbook ids
     */
    @Query(fields = "{id: 1}")
    List<Logbook> findAllByWriteAllIsTrue();
}
