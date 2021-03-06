// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.cosmos.examples.crudquickstart.async;


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
import com.azure.cosmos.examples.common.Families;
import com.azure.cosmos.examples.common.Family;
import com.azure.cosmos.models.CosmosAsyncContainerResponse;
import com.azure.cosmos.models.CosmosAsyncDatabaseResponse;
import com.azure.cosmos.models.CosmosAsyncItemResponse;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosContainerRequestOptions;
import com.azure.cosmos.models.FeedOptions;
import com.azure.cosmos.models.PartitionKey;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class SampleCRUDQuickstartAsync {

    private CosmosAsyncClient client;

    private final String databaseName = "AzureSampleFamilyDB";
    private final String containerName = "FamilyContainer";

    private CosmosAsyncDatabase database;
    private CosmosAsyncContainer container;

    protected static Logger logger = LoggerFactory.getLogger(SampleChangeFeedProcessor.class.getSimpleName());

    public void close() {
        client.close();
    }

    /**
     * Run a Hello CosmosDB console application.
     * <p>
     * This is a simple sample application intended to demonstrate Create, Read, Update, Delete (CRUD) operations
     * with Azure Cosmos DB Java SDK, as applied to databases, containers and items. This sample will
     * 1. Create asynchronous client, database and container instances
     * 2. Create several items
     * 3. Upsert one of the items
     * 4. Perform a query over the items
     * 5. Delete an item
     * 6. Delete the Cosmos DB database and container resources and close the client.
     */
    //  <Main>
    public static void main(String[] args) {
        SampleCRUDQuickstartAsync p = new SampleCRUDQuickstartAsync();

        try {
            logger.info("Starting ASYNC main");
            p.getStartedDemo();
            logger.info("Demo complete, please hold while resources are released");
        } catch (Exception e) {
            e.printStackTrace();
            logger.error(String.format("Cosmos getStarted failed with %s", e));
        } finally {
            logger.info("Closing the client");
            p.shutdown();
        }
    }

    //  </Main>

    private void getStartedDemo() throws Exception {

        logger.info("Using Azure Cosmos DB endpoint: " + AccountSettings.HOST);

        ConnectionPolicy defaultPolicy = ConnectionPolicy.getDefaultPolicy();
        //  Setting the preferred location to Cosmos DB Account region
        //  West US is just an example. User should set preferred location to the Cosmos DB region closest to the application
        defaultPolicy.setPreferredLocations(Lists.newArrayList("West US"));

        //  Create async client
        //  <CreateAsyncClient>
        client = new CosmosClientBuilder()
                .setEndpoint(AccountSettings.HOST)
                .setKey(AccountSettings.MASTER_KEY)
                .setConnectionPolicy(defaultPolicy)
                .setConsistencyLevel(ConsistencyLevel.EVENTUAL)
                .buildAsyncClient();

        //  </CreateAsyncClient>

        createDatabaseIfNotExists();
        createContainerIfNotExists();

        Family andersenFamilyItem = Families.getAndersenFamilyItem();
        Family wakefieldFamilyItem = Families.getWakefieldFamilyItem();
        Family johnsonFamilyItem = Families.getJohnsonFamilyItem();
        Family smithFamilyItem = Families.getSmithFamilyItem();

        //  Setup family items to create
        Flux<Family> familiesToCreate = Flux.just(andersenFamilyItem,
                wakefieldFamilyItem,
                johnsonFamilyItem,
                smithFamilyItem);

        // Creates several items in the container
        createFamilies(familiesToCreate);

        // Upsert one of the items in the container
        upsertFamily(wakefieldFamilyItem);

        familiesToCreate = Flux.just(andersenFamilyItem,
                wakefieldFamilyItem,
                johnsonFamilyItem,
                smithFamilyItem);

        logger.info("Reading items.");
        readItems(familiesToCreate);

        logger.info("Querying items.");
        queryItems();

        logger.info("Deleting an item.");
        deleteItem(andersenFamilyItem);
    }

    private void createDatabaseIfNotExists() throws Exception {
        logger.info("Create database " + databaseName + " if not exists.");

        //  Create database if not exists
        //  <CreateDatabaseIfNotExists>
        Mono<CosmosAsyncDatabaseResponse> databaseIfNotExists = client.createDatabaseIfNotExists(databaseName);
        databaseIfNotExists.flatMap(databaseResponse -> {
            database = databaseResponse.getDatabase();
            logger.info("Checking database " + database.getId() + " completed!\n");
            return Mono.empty();
        }).block();
        //  </CreateDatabaseIfNotExists>
    }

    private void createContainerIfNotExists() throws Exception {
        logger.info("Create container " + containerName + " if not exists.");

        //  Create container if not exists
        //  <CreateContainerIfNotExists>

        CosmosContainerProperties containerProperties = new CosmosContainerProperties(containerName, "/lastName");
        Mono<CosmosAsyncContainerResponse> containerIfNotExists = database.createContainerIfNotExists(containerProperties, 400);

        //  Create container with 400 RU/s
        CosmosAsyncContainerResponse cosmosContainerResponse = containerIfNotExists.block();
        container = cosmosContainerResponse.getContainer();
        //  </CreateContainerIfNotExists>

        //Modify existing container
        containerProperties = cosmosContainerResponse.getProperties();
        Mono<CosmosAsyncContainerResponse> propertiesReplace = container.replace(containerProperties, new CosmosContainerRequestOptions());
        propertiesReplace.flatMap(containerResponse -> {
            logger.info("setupContainer(): Container " + container.getId() + " in " + database.getId() +
                    "has been updated with it's new properties.");
            return Mono.empty();
        }).onErrorResume((exception) -> {
            logger.error("setupContainer(): Unable to update properties for container " + container.getId() +
                    " in database " + database.getId() +
                    ". e: " + exception.getLocalizedMessage());
            return Mono.empty();
        }).block();

    }

    private void createFamilies(Flux<Family> families) throws Exception {

        //  <CreateItem>

        final CountDownLatch completionLatch = new CountDownLatch(1);

        //  Combine multiple item inserts, associated success println's, and a final aggregate stats println into one Reactive stream.
        families.flatMap(family -> {
            return container.createItem(family);
        }) //Flux of item request responses
                .flatMap(itemResponse -> {
                    logger.info(String.format("Created item with request charge of %.2f within" +
                                    " duration %s",
                            itemResponse.getRequestCharge(), itemResponse.getRequestLatency()));
                    logger.info(String.format("Item ID: %s\n", itemResponse.getItem().getId()));
                    return Mono.just(itemResponse.getRequestCharge());
                }) //Flux of request charges
                .reduce(0.0,
                        (charge_n, charge_nplus1) -> charge_n + charge_nplus1
                ) //Mono of total charge - there will be only one item in this stream
                .subscribe(charge -> {
                            logger.info(String.format("Created items with total request charge of %.2f\n",
                                    charge));
                        },
                        err -> {
                            if (err instanceof CosmosClientException) {
                                //Client-specific errors
                                CosmosClientException cerr = (CosmosClientException) err;
                                cerr.printStackTrace();
                                logger.error(String.format("Read Item failed with %s\n", cerr));
                            } else {
                                //General errors
                                err.printStackTrace();
                            }

                            completionLatch.countDown();
                        },
                        () -> {
                            completionLatch.countDown();
                        }
                ); //Preserve the total charge and print aggregate charge/item count stats.

        try {
            completionLatch.await();
        } catch (InterruptedException err) {
            throw new AssertionError("Unexpected Interruption", err);
        }

        //  </CreateItem>
    }

    private void upsertFamily(Family family_to_upsert) {
        //Modify a field of the family object
        logger.info(String.format("Upserting the item with id %s after modifying the isRegistered field...", family_to_upsert.getId()));
        family_to_upsert.setRegistered(!family_to_upsert.isRegistered());

        //Upsert the modified item
        Mono.just(family_to_upsert).flatMap(item -> {
            CosmosAsyncItemResponse<Family> item_resp = container.upsertItem(family_to_upsert).block();

            //  Get upsert request charge and other properties like latency, and diagnostics strings, etc.
            logger.info(String.format("Upserted item with request charge of %.2f within duration %s",
                    item_resp.getRequestCharge(), item_resp.getRequestLatency()));

            return Mono.empty();
        }).subscribe();
    }

    private void readItems(Flux<Family> familiesToCreate) {
        //  Using partition key for point read scenarios.
        //  This will help fast look up of items because of partition key
        //  <ReadItem>

        final CountDownLatch completionLatch = new CountDownLatch(1);

        familiesToCreate.flatMap(family -> {
            Mono<CosmosAsyncItemResponse<Family>> asyncItemResponseMono = container.readItem(family.getId(), new PartitionKey(family.getLastName()), Family.class);
            return asyncItemResponseMono;
        })
                .subscribe(
                        itemResponse -> {
                            double requestCharge = itemResponse.getRequestCharge();
                            Duration requestLatency = itemResponse.getRequestLatency();
                            logger.info(String.format("Item successfully read with id %s with a charge of %.2f and within duration %s",
                                    itemResponse.getItem().getId(), requestCharge, requestLatency));
                        },
                        err -> {
                            if (err instanceof CosmosClientException) {
                                //Client-specific errors
                                CosmosClientException cerr = (CosmosClientException) err;
                                cerr.printStackTrace();
                                logger.error(String.format("Read Item failed with %s\n", cerr));
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

        try {
            completionLatch.await();
        } catch (InterruptedException err) {
            throw new AssertionError("Unexpected Interruption", err);
        }

        //  </ReadItem>
    }

    private void queryItems() {
        //  <QueryItems>
        // Set some common query options

        FeedOptions queryOptions = new FeedOptions();
        queryOptions.setMaxItemCount(10);
        //queryOptions.setEnableCrossPartitionQuery(true); //No longer needed in SDK v4
        //  Set populate query metrics to get metrics around query executions
        queryOptions.setPopulateQueryMetrics(true);

        CosmosPagedFlux<Family> pagedFluxResponse = container.queryItems(
                "SELECT * FROM Family WHERE Family.lastName IN ('Andersen', 'Wakefield', 'Johnson')", queryOptions, Family.class);

        final CountDownLatch completionLatch = new CountDownLatch(1);

        pagedFluxResponse.byPage().subscribe(
                fluxResponse -> {
                    logger.info("Got a page of query result with " +
                            fluxResponse.getResults().size() + " items(s)"
                            + " and request charge of " + fluxResponse.getRequestCharge());

                    logger.info("Item Ids " + fluxResponse
                            .getResults()
                            .stream()
                            .map(Family::getId)
                            .collect(Collectors.toList()));
                },
                err -> {
                    if (err instanceof CosmosClientException) {
                        //Client-specific errors
                        CosmosClientException cerr = (CosmosClientException) err;
                        cerr.printStackTrace();
                        logger.error(String.format("Read Item failed with %s\n", cerr));
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

        try {
            completionLatch.await();
        } catch (InterruptedException err) {
            throw new AssertionError("Unexpected Interruption", err);
        }

        // </QueryItems>
    }

    private void deleteItem(Family item) {
        container.deleteItem(item.getId(), new PartitionKey(item.getLastName())).block();
    }

    private void shutdown() {
        try {
            //Clean shutdown
            logger.info("Deleting Cosmos DB resources");
            logger.info("-Deleting container...");
            if (container != null)
                container.delete().subscribe();
            logger.info("-Deleting database...");
            if (database != null)
                database.delete().subscribe();
            logger.info("-Closing the client...");
        } catch (Exception err) {
            logger.error("Deleting Cosmos DB resources failed, will still attempt to close the client. See stack trace below.");
            err.printStackTrace();
        }
        client.close();
        logger.info("Done.");
    }
}

