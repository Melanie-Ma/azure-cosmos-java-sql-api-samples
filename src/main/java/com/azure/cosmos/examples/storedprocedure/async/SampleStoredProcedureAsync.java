// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.


// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.cosmos.examples.storedprocedure.async;

import com.azure.cosmos.ConnectionPolicy;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosClientException;
import com.azure.cosmos.CosmosPagedFlux;
import com.azure.cosmos.examples.changefeed.SampleChangeFeedProcessor;
import com.azure.cosmos.examples.common.AccountSettings;
import com.azure.cosmos.examples.common.CustomPOJO;
import com.azure.cosmos.models.CosmosAsyncItemResponse;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosStoredProcedureProperties;
import com.azure.cosmos.models.CosmosStoredProcedureRequestOptions;
import com.azure.cosmos.models.FeedOptions;
import com.azure.cosmos.models.PartitionKey;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.concurrent.CountDownLatch;

public class SampleStoredProcedureAsync {

    private CosmosAsyncClient client;

    private final String databaseName = "SprocTestDB";
    private final String containerName = "SprocTestContainer";

    private CosmosAsyncDatabase database;
    private CosmosAsyncContainer container;

    private String sprocId;

    protected static Logger logger = LoggerFactory.getLogger(SampleChangeFeedProcessor.class.getSimpleName());

    public void close() {
        client.close();
    }

    /**
     * Stored Procedure Example
     * <p>
     * This sample code demonstrates creation, execution, and effects of stored procedures
     * using Java SDK. A stored procedure is created which will insert a JSON object into
     * a Cosmos DB container. The sample executes the stored procedure and then performs
     * a point-read to confirm that the stored procedure had the intended effect.
     */
    //  <Main>
    public static void main(String[] args) {
        SampleStoredProcedureAsync p = new SampleStoredProcedureAsync();

        try {
            p.sprocDemo();
            logger.info("Demo complete, please hold while resources are released");
            p.shutdown();
            logger.info("Done.\n");
        } catch (Exception e) {
            e.printStackTrace();
            logger.info(String.format("Cosmos getStarted failed with %s", e));
            p.close();
        } finally {
        }
    }

    //  </Main>

    private void sprocDemo() throws Exception {
        //Setup client, DB, and the container for which we will create stored procedures
        //The container partition key will be id
        setUp();

        //Create stored procedure and list all stored procedures that have been created.
        createStoredProcedure();
        readAllSprocs();

        //Execute the stored procedure, which we expect will create an item with id test_doc
        executeStoredProcedure();

        //Perform a point-read to confirm that the item with id test_doc exists
        logger.info("Checking that a document was created by the stored procedure...");
        CosmosAsyncItemResponse<CustomPOJO> test_resp =
                container.readItem("test_doc", new PartitionKey("test_doc"), CustomPOJO.class).block();
        logger.info(String.format(
                "Status return value of point-read for document created by stored procedure (200 indicates success): %d", test_resp.getStatusCode()));
    }

    public void setUp() throws Exception {
        logger.info("Using Azure Cosmos DB endpoint: " + AccountSettings.HOST);

        ConnectionPolicy defaultPolicy = ConnectionPolicy.getDefaultPolicy();
        //  Setting the preferred location to Cosmos DB Account region
        //  West US is just an example. User should set preferred location to the Cosmos DB region closest to the application
        defaultPolicy.setPreferredLocations(Lists.newArrayList("West US"));

        //  Create sync client
        //  <CreateSyncClient>
        client = new CosmosClientBuilder()
                .setEndpoint(AccountSettings.HOST)
                .setKey(AccountSettings.MASTER_KEY)
                .setConnectionPolicy(defaultPolicy)
                .setConsistencyLevel(ConsistencyLevel.EVENTUAL)
                .buildAsyncClient();

        logger.info("Create database " + databaseName + " with container " + containerName + " if either does not already exist.\n");

        client.createDatabaseIfNotExists(databaseName).flatMap(databaseResponse -> {
            database = databaseResponse.getDatabase();
            return Mono.empty();
        }).block();

        CosmosContainerProperties containerProperties =
                new CosmosContainerProperties(containerName, "/id");
        database.createContainerIfNotExists(containerProperties, 400).flatMap(containerResponse -> {
            container = containerResponse.getContainer();
            return Mono.empty();
        }).block();
    }

    public void shutdown() throws Exception {
        //Safe clean & close
        deleteStoredProcedure();
    }

    public void createStoredProcedure() throws Exception {
        logger.info("Creating stored procedure...\n");

        sprocId = "createMyDocument";
        String sprocBody = "function createMyDocument() {\n" +
                "var documentToCreate = {\"id\":\"test_doc\"}\n" +
                "var context = getContext();\n" +
                "var collection = context.getCollection();\n" +
                "var accepted = collection.createDocument(collection.getSelfLink(), documentToCreate,\n" +
                "    function (err, documentCreated) {\n" +
                "if (err) throw new Error('Error' + err.message);\n" +
                "context.getResponse().setBody(documentCreated.id)\n" +
                "});\n" +
                "if (!accepted) return;\n" +
                "}";
        CosmosStoredProcedureProperties storedProcedureDef = new CosmosStoredProcedureProperties(sprocId, sprocBody);
        container.getScripts()
                .createStoredProcedure(storedProcedureDef,
                        new CosmosStoredProcedureRequestOptions()).block();
    }

    private void readAllSprocs() throws Exception {

        FeedOptions feedOptions = new FeedOptions();
        CosmosPagedFlux<CosmosStoredProcedureProperties> fluxResponse =
                container.getScripts().readAllStoredProcedures(feedOptions);

        final CountDownLatch completionLatch = new CountDownLatch(1);


        fluxResponse.flatMap(storedProcedureProperties -> {
            logger.info(String.format("Stored Procedure: %s\n", storedProcedureProperties.getId()));
            return Mono.empty();
        }).subscribe(
                s -> {
                },
                err -> {
                    if (err instanceof CosmosClientException) {
                        //Client-specific errors
                        CosmosClientException cerr = (CosmosClientException) err;
                        cerr.printStackTrace();
                        logger.info(String.format("Read Item failed with %s\n", cerr));
                    } else {
                        //General errors
                        err.printStackTrace();
                    }

                    completionLatch.countDown();
                },
                () -> {
                    completionLatch.countDown();
                }
        );

        completionLatch.await();
    }

    public void executeStoredProcedure() throws Exception {
        logger.info(String.format("Executing stored procedure %s...\n\n", sprocId));

        CosmosStoredProcedureRequestOptions options = new CosmosStoredProcedureRequestOptions();
        options.setPartitionKey(new PartitionKey("test_doc"));

        container.getScripts()
                .getStoredProcedure(sprocId)
                .execute(null, options)
                .flatMap(executeResponse -> {
                    logger.info(String.format("Stored procedure %s returned %s (HTTP %d), at cost %.3f RU.\n",
                            sprocId,
                            executeResponse.getResponseAsString(),
                            executeResponse.getStatusCode(),
                            executeResponse.getRequestCharge()));
                    return Mono.empty();
                }).block();
    }

    public void deleteStoredProcedure() throws Exception {
        logger.info("-Deleting stored procedure...\n");
        container.getScripts()
                .getStoredProcedure(sprocId)
                .delete().block();
        logger.info("-Deleting database...\n");
        database.delete().block();
        logger.info("-Closing client instance...\n");
        client.close();
    }
}
