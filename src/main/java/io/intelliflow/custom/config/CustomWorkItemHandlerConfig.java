package io.intelliflow.custom.config;

import com.mongodb.client.MongoClient;
import io.intelliflow.custom.*;
import io.intelliflow.service.KeyVaultService;
import io.intelliflow.service.RouterService;
import io.intelliflow.utils.ObjectTransformer;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.kie.kogito.process.impl.DefaultWorkItemHandlerConfig;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class CustomWorkItemHandlerConfig extends DefaultWorkItemHandlerConfig {

    @Inject
    @RestClient
    RouterService routerService;

    @Inject
    @RestClient
    KeyVaultService keyVaultService;

    @Inject
    ObjectTransformer objectTransformer;

    @Inject
    MongoClient mongodbClient;

    @PostConstruct
    public void registerTaskHandlers() {
        register("DataOperation", new DataOperations());
        register("Rest Operation", new RestHandler(keyVaultService));
        register("Soap Operation", new SoapHandler(keyVaultService));
        register("SQL Operation", new JDBCHandler(keyVaultService));
        register("Generate Document", new DocTemplate());
        register("Email", new EmailHandler());
        register("AIAgent", new AIAgentHandler());
        register("CommunicationTask" , new CommunicationTaskHandler(routerService,objectTransformer,mongodbClient));
    }
}