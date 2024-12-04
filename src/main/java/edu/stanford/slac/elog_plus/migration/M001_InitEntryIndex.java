package edu.stanford.slac.elog_plus.migration;


import edu.stanford.slac.elog_plus.model.Entry;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

@AllArgsConstructor
@ChangeUnit(id = "entry-index", order = "1", author = "bisegni")
public class M001_InitEntryIndex {
    private final MongoTemplate mongoTemplate;

    @Execution
    public void changeSet() {
        //entry index
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "logbooks",
                        Sort.Direction.ASC
                )
        );

        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "summarizes",
                        Sort.Direction.ASC
                )
        );

        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new TextIndexDefinition.TextIndexDefinitionBuilder()
                        .onField("title")
                        .onField("text")
                        .build()
        );

        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "tags",
                        Sort.Direction.ASC
                )
        );
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "attachments",
                        Sort.Direction.ASC
                )
        );
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "followUps",
                        Sort.Direction.ASC
                )
        );
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "loggedAt",
                        Sort.Direction.ASC
                )
        );
        MongoDDLOps.createIndex(
                Entry.class,
                mongoTemplate,
                new Index().on(
                        "eventAt",
                        Sort.Direction.ASC
                )
        );
    }

    @RollbackExecution
    public void rollback() {

    }
}
