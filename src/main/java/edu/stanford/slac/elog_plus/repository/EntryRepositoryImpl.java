package edu.stanford.slac.elog_plus.repository;

import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.QueryParameterWithAnchor;
import lombok.AllArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.emptyList;

@Repository
@AllArgsConstructor
public class EntryRepositoryImpl implements EntryRepositoryCustom {
    final private MongoTemplate mongoTemplate;

    private Entry getEntryByIDWithOnlyDate(String id) {
        Entry result = null;
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(id)
        ).fields().include("eventAt", "loggedAt");
        return mongoTemplate.findOne(q, Entry.class);
    }

    @Override
    public List<Entry> searchAll(QueryParameterWithAnchor queryWithAnchor) {
        if (queryWithAnchor.getContextSize() != null && queryWithAnchor.getLimit() == null) {
            throw ControllerLogicException
                    .builder()
                    .errorCode(-1)
                    .errorMessage("logs before count cannot be used without an anchor id")
                    .errorDomain("LogRepositoryImpl::searchUsingAnchor")
                    .build();
        }
        if (queryWithAnchor.getLimit() == null) {
            throw ControllerLogicException
                    .builder()
                    .errorCode(-2)
                    .errorMessage("the logs after count is mandatory")
                    .errorDomain("LogRepositoryImpl::searchUsingAnchor")
                    .build();
        }

        Entry anchorEntry = null;
        if(queryWithAnchor.getAnchorID() !=null) {
            anchorEntry = getEntryByIDWithOnlyDate(queryWithAnchor.getAnchorID());
        }

        List<Criteria> allCriteria = new ArrayList<>();
        if (!queryWithAnchor.getLogbooks().isEmpty()) {
            allCriteria.add(
                    Criteria.where("logbooks").in(
                            queryWithAnchor.getLogbooks()
                    )
            );
        }

        if (queryWithAnchor.getOriginId() != null) {
            allCriteria.add(
                    Criteria.where("originId").is(
                            queryWithAnchor.getOriginId()
                    )
            );
        }

        if (!queryWithAnchor.getTags().isEmpty()) {
            allCriteria.add(
                    queryWithAnchor.getRequireAllTags()?
                    Criteria.where("tags").all(
                            queryWithAnchor.getTags()
                    ):Criteria.where("tags").in(
                            queryWithAnchor.getTags()
                    )
            );
        }
        if (queryWithAnchor.getHideSummaries()!= null && queryWithAnchor.getHideSummaries()) {
            allCriteria.add(
                    Criteria.where("summarizes").exists(false)
            );
        }

        if(queryWithAnchor.getAuthors()!=null && !queryWithAnchor.getAuthors().isEmpty()) {
            allCriteria.add(
                    Criteria.where("userName").in(queryWithAnchor.getAuthors())
            );
        }

        // supersede criteria
        allCriteria.add(
                Criteria.where("supersededBy").exists(false)
        );

        List<Entry> logsBeforeAnchor = new ArrayList<>();
        List<Entry> logsAfterAnchor = new ArrayList<>();

        if (
                queryWithAnchor.getContextSize() != null
                        && queryWithAnchor.getContextSize() > 0
                        && queryWithAnchor.getAnchorID() != null
        ) {
            getEntriesBeforeAnchor(queryWithAnchor, allCriteria, anchorEntry, logsBeforeAnchor);
        }

        if (queryWithAnchor.getLimit() != null && queryWithAnchor.getLimit() > 0) {
            logsAfterAnchor = getEntriesAfterAnchor(queryWithAnchor, allCriteria, anchorEntry);
        }

        logsBeforeAnchor.addAll(logsAfterAnchor);
        return logsBeforeAnchor;
    }

    @NotNull
    private List<Entry> getEntriesAfterAnchor(QueryParameterWithAnchor queryWithAnchor, List<Criteria> allCriteria, Entry anchorEntry) {
        List<Entry> logsAfterAnchor;
        List<Criteria> localAllCriteria = allCriteria;
        Query q = getDefaultQuery(queryWithAnchor.getSearch());
        applyDateCriteriaForLimitEntries(localAllCriteria, queryWithAnchor);
        if(anchorEntry != null) {
            q.addCriteria(
                    Criteria.where(
                            getSortedField(queryWithAnchor)
                    ).lt(
                            getAnchorValueDate(queryWithAnchor, anchorEntry)
                    )
            );
        }
        q.addCriteria(new Criteria().andOperator(
                localAllCriteria
                )
        ).with(
                Sort.by(
                        Sort.Direction.DESC, getSortedField(queryWithAnchor))
        ).limit(queryWithAnchor.getLimit());
        logsAfterAnchor = mongoTemplate.find(
                q,
                Entry.class
        );
        return logsAfterAnchor;
    }

    private void getEntriesBeforeAnchor(QueryParameterWithAnchor queryWithAnchor, List<Criteria> allCriteria, Entry anchorEntry, List<Entry> logsBeforeAnchor) {
        List<Criteria> localAllCriteria = allCriteria;
        Query q = getDefaultQuery(queryWithAnchor.getSearch());
        q.addCriteria(
                Criteria.where(
                        getSortedField(queryWithAnchor)
                ).gte(
                        getAnchorValueDate(queryWithAnchor, anchorEntry)
                )
        );
        applyDateCriteriaForContextEntries(localAllCriteria, queryWithAnchor);
        q.addCriteria(
                // all general criteria
                new Criteria().andOperator(
                        localAllCriteria
                )

        ).with(
                Sort.by(
                        Sort.Direction.ASC, getSortedField(queryWithAnchor))
        ).limit(queryWithAnchor.getContextSize());
        logsBeforeAnchor.addAll(mongoTemplate.find(
                        q,
                        Entry.class
                )
        );
        Collections.reverse(logsBeforeAnchor);
    }

    @Override
    public List<String> getAllTags() {
        return mongoTemplate.findDistinct(new Query(), "tags", Entry.class, String.class);
    }

    @Override
    public void setSupersededBy(String id, String supersededById) {
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(id)
        );
        Update u = new Update();
        u.set("supersededBy", supersededById);
        mongoTemplate.updateFirst(q, u, Entry.class);
    }

    @Override
    public List<String> findReferencesBySourceId(String id) {
        Query q = new Query();
        q.addCriteria(
                Criteria.where("id").is(id)
        );
        q.fields().include("references");
        Entry e = mongoTemplate.findOne(q, Entry.class);
        return e!=null?e.getReferences():emptyList();
    }

    private Query getDefaultQuery(String textSearch) {
        if (textSearch != null && !textSearch.isEmpty()) {
            //{$text: {$search:'log' }}
            return TextQuery.queryText(TextCriteria.forDefaultLanguage()
                    .matchingAny(textSearch.split(" "))
            );
        } else {
            return new Query();
        }
    }

    private void applyDateCriteriaForContextEntries(List<Criteria> allCriteria, QueryParameterWithAnchor queryWithAnchor) {
        if(queryWithAnchor.getEndDate() != null) {
            allCriteria.add(
                    Criteria.where(getSortedField(queryWithAnchor))
                            .gte(queryWithAnchor.getEndDate())
            );
        }
    }

    private void applyDateCriteriaForLimitEntries(List<Criteria> allCriteria, QueryParameterWithAnchor queryWithAnchor) {
        if (queryWithAnchor.getEndDate() != null) {
            allCriteria.add(
                    Criteria.where(getSortedField(queryWithAnchor)).lte(queryWithAnchor.getEndDate())
            );
        }
        if (queryWithAnchor.getStartDate() != null) {
            allCriteria.add(
                    Criteria.where(getSortedField(queryWithAnchor)).gte(queryWithAnchor.getStartDate())
            );
        }


    }

    private String getSortedField(QueryParameterWithAnchor queryWithAnchor) {
        return (queryWithAnchor.getSortByLogDate() != null && queryWithAnchor.getSortByLogDate()) ? "loggedAt" : "eventAt";
    }

    private LocalDateTime getAnchorValueDate(QueryParameterWithAnchor queryWithAnchor, Entry anchorEntry) {
        return (queryWithAnchor.getSortByLogDate() != null && queryWithAnchor.getSortByLogDate()) ?
                anchorEntry.getLoggedAt() : anchorEntry.getEventAt();
    }
}
