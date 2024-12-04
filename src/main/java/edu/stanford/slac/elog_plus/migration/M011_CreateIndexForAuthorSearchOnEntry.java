package edu.stanford.slac.elog_plus.migration;

import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Log4j2
@AllArgsConstructor
@ChangeUnit(id = "author-search-index", order = "11", author = "bisegni")
public class M011_CreateIndexForAuthorSearchOnEntry {
    private final MongoTemplate mongoTemplate;

    @Execution
    public void changeSet() {
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index()
                        .on(
                                "userName",
                                Sort.Direction.ASC
                        )
                        .named("authorSearch")
        );
    }

    @RollbackExecution
    public void rollback() {
    }

}