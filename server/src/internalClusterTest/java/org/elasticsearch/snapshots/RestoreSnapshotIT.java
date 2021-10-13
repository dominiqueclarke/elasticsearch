/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.snapshots;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.blobstore.FileRestoreContext;
import org.elasticsearch.repositories.fs.FsRepository;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.MockLogAppender;
import org.elasticsearch.xcontent.XContentFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.index.IndexSettings.INDEX_REFRESH_INTERVAL_SETTING;
import static org.elasticsearch.index.IndexSettings.INDEX_SOFT_DELETES_SETTING;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.indices.recovery.RecoverySettings.INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertIndexTemplateExists;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertIndexTemplateMissing;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertRequestBuilderThrows;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class RestoreSnapshotIT extends AbstractSnapshotIntegTestCase {

    public void testParallelRestoreOperations() {
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String repoName = "test-restore-snapshot-repo";
        String snapshotName1 = "test-restore-snapshot1";
        String snapshotName2 = "test-restore-snapshot2";
        Path absolutePath = randomRepoPath().toAbsolutePath();
        logger.info("Path [{}]", absolutePath);
        String restoredIndexName1 = indexName1 + "-restored";
        String restoredIndexName2 = indexName2 + "-restored";
        String expectedValue = "expected";

        Client client = client();
        // Write a document
        String docId = Integer.toString(randomInt());
        index(indexName1, "_doc", docId, "value", expectedValue);

        String docId2 = Integer.toString(randomInt());
        index(indexName2, "_doc", docId2, "value", expectedValue);

        createRepository(repoName, "fs", absolutePath);

        createSnapshot(repoName, snapshotName1, Collections.singletonList(indexName1));
        createSnapshot(repoName, snapshotName2, Collections.singletonList(indexName2));

        RestoreSnapshotResponse restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName1)
            .setWaitForCompletion(false)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .get();
        RestoreSnapshotResponse restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName2)
            .setWaitForCompletion(false)
            .setRenamePattern(indexName2)
            .setRenameReplacement(restoredIndexName2)
            .get();
        assertThat(restoreSnapshotResponse1.status(), equalTo(RestStatus.ACCEPTED));
        assertThat(restoreSnapshotResponse2.status(), equalTo(RestStatus.ACCEPTED));
        ensureGreen(restoredIndexName1, restoredIndexName2);
        assertThat(client.prepareGet(restoredIndexName1, "_doc", docId).get().isExists(), equalTo(true));
        assertThat(client.prepareGet(restoredIndexName2, "_doc", docId2).get().isExists(), equalTo(true));
    }

    public void testParallelRestoreOperationsFromSingleSnapshot() throws Exception {
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String repoName = "test-restore-snapshot-repo";
        String snapshotName = "test-restore-snapshot";
        Path absolutePath = randomRepoPath().toAbsolutePath();
        logger.info("Path [{}]", absolutePath);
        String restoredIndexName1 = indexName1 + "-restored";
        String restoredIndexName2 = indexName2 + "-restored";
        String expectedValue = "expected";

        Client client = client();
        // Write a document
        String docId = Integer.toString(randomInt());
        index(indexName1, "_doc", docId, "value", expectedValue);

        String docId2 = Integer.toString(randomInt());
        index(indexName2, "_doc", docId2, "value", expectedValue);

        createRepository(repoName, "fs", absolutePath);

        createSnapshot(repoName, snapshotName, Arrays.asList(indexName1, indexName2));

        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .execute();

        boolean sameSourceIndex = randomBoolean();

        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setIndices(sameSourceIndex ? indexName1 : indexName2)
            .setRenamePattern(sameSourceIndex ? indexName1 : indexName2)
            .setRenameReplacement(restoredIndexName2)
            .execute();
        assertThat(restoreSnapshotResponse1.get().status(), equalTo(RestStatus.ACCEPTED));
        assertThat(restoreSnapshotResponse2.get().status(), equalTo(RestStatus.ACCEPTED));
        ensureGreen(restoredIndexName1, restoredIndexName2);
        assertThat(client.prepareGet(restoredIndexName1, "_doc", docId).get().isExists(), equalTo(true));
        assertThat(client.prepareGet(restoredIndexName2, "_doc", sameSourceIndex ? docId : docId2).get().isExists(), equalTo(true));
    }

    public void testRestoreIncreasesPrimaryTerms() {
        final String indexName = randomAlphaOfLengthBetween(5, 10).toLowerCase(Locale.ROOT);
        createIndex(indexName, indexSettingsNoReplicas(2).build());
        ensureGreen(indexName);

        if (randomBoolean()) {
            // open and close the index to increase the primary terms
            for (int i = 0; i < randomInt(3); i++) {
                assertAcked(client().admin().indices().prepareClose(indexName));
                assertAcked(client().admin().indices().prepareOpen(indexName));
            }
        }

        final IndexMetadata indexMetadata = clusterAdmin().prepareState()
            .clear()
            .setIndices(indexName)
            .setMetadata(true)
            .get()
            .getState()
            .metadata()
            .index(indexName);
        assertThat(indexMetadata.getSettings().get(IndexMetadata.SETTING_HISTORY_UUID), nullValue());
        final int numPrimaries = getNumShards(indexName).numPrimaries;
        final Map<Integer, Long> primaryTerms = IntStream.range(0, numPrimaries)
            .boxed()
            .collect(Collectors.toMap(shardId -> shardId, indexMetadata::primaryTerm));

        createRepository("test-repo", "fs");
        createSnapshot("test-repo", "test-snap", Collections.singletonList(indexName));

        assertAcked(client().admin().indices().prepareClose(indexName));

        final RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .get();
        assertThat(restoreSnapshotResponse.getRestoreInfo().successfulShards(), equalTo(numPrimaries));
        assertThat(restoreSnapshotResponse.getRestoreInfo().failedShards(), equalTo(0));

        final IndexMetadata restoredIndexMetadata = clusterAdmin().prepareState()
            .clear()
            .setIndices(indexName)
            .setMetadata(true)
            .get()
            .getState()
            .metadata()
            .index(indexName);
        for (int shardId = 0; shardId < numPrimaries; shardId++) {
            assertThat(restoredIndexMetadata.primaryTerm(shardId), greaterThan(primaryTerms.get(shardId)));
        }
        assertThat(restoredIndexMetadata.getSettings().get(IndexMetadata.SETTING_HISTORY_UUID), notNullValue());
    }

    public void testRestoreWithDifferentMappingsAndSettings() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("--> create index with baz field");
        assertAcked(
            prepareCreate(
                "test-idx",
                2,
                Settings.builder()
                    .put(indexSettings())
                    .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
                    .put("refresh_interval", 10, TimeUnit.SECONDS)
            )
        );

        NumShards numShards = getNumShards("test-idx");

        assertAcked(client().admin().indices().preparePutMapping("test-idx").setType("_doc").setSource("baz", "type=text"));
        ensureGreen();

        createSnapshot("test-repo", "test-snap", Collections.singletonList("test-idx"));

        logger.info("--> delete the index and recreate it with foo field");
        cluster().wipeIndices("test-idx");
        assertAcked(
            prepareCreate(
                "test-idx",
                2,
                Settings.builder()
                    .put(SETTING_NUMBER_OF_SHARDS, numShards.numPrimaries)
                    .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
                    .put("refresh_interval", 5, TimeUnit.SECONDS)
            )
        );
        assertAcked(client().admin().indices().preparePutMapping("test-idx").setType("_doc").setSource("foo", "type=text"));
        ensureGreen();

        logger.info("--> close index");
        client().admin().indices().prepareClose("test-idx").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that old mapping is restored");
        MappingMetadata mappings = clusterAdmin().prepareState().get().getState().getMetadata().getIndices().get("test-idx").mapping();
        assertThat(mappings.sourceAsMap().toString(), containsString("baz"));
        assertThat(mappings.sourceAsMap().toString(), not(containsString("foo")));

        logger.info("--> assert that old settings are restored");
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", "index.refresh_interval"), equalTo("10s"));
    }

    public void testRestoreAliases() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("--> create test indices");
        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        logger.info("--> create aliases");
        assertAcked(
            client().admin()
                .indices()
                .prepareAliases()
                .addAlias("test-idx-1", "alias-123")
                .addAlias("test-idx-2", "alias-123")
                .addAlias("test-idx-3", "alias-123")
                .addAlias("test-idx-1", "alias-1")
                .get()
        );

        assertFalse(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());

        createSnapshot("test-repo", "test-snap", Collections.emptyList());

        logger.info("-->  delete all indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2", "test-idx-3");
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("--> restore snapshot with aliases");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(
            restoreSnapshotResponse.getRestoreInfo().successfulShards(),
            allOf(greaterThan(0), equalTo(restoreSnapshotResponse.getRestoreInfo().totalShards()))
        );

        logger.info("--> check that aliases are restored");
        assertFalse(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertFalse(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("-->  update aliases");
        assertAcked(client().admin().indices().prepareAliases().removeAlias("test-idx-3", "alias-123"));
        assertAcked(client().admin().indices().prepareAliases().addAlias("test-idx-3", "alias-3"));

        logger.info("-->  delete and close indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2");
        assertAcked(client().admin().indices().prepareClose("test-idx-3"));
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("--> restore snapshot without aliases");
        restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .setIncludeAliases(false)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(
            restoreSnapshotResponse.getRestoreInfo().successfulShards(),
            allOf(greaterThan(0), equalTo(restoreSnapshotResponse.getRestoreInfo().totalShards()))
        );

        logger.info("--> check that aliases are not restored and existing aliases still exist");
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());
        assertFalse(client().admin().indices().prepareGetAliases("alias-3").get().getAliases().isEmpty());
    }

    public void testRestoreTemplates() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("-->  creating test template");
        assertThat(
            client().admin()
                .indices()
                .preparePutTemplate("test-template")
                .setPatterns(Collections.singletonList("te*"))
                .addMapping(
                    "_doc",
                    XContentFactory.jsonBuilder()
                        .startObject()
                        .startObject("_doc")
                        .startObject("properties")
                        .startObject("field1")
                        .field("type", "text")
                        .field("store", true)
                        .endObject()
                        .startObject("field2")
                        .field("type", "keyword")
                        .field("store", true)
                        .endObject()
                        .endObject()
                        .endObject()
                        .endObject()
                )
                .get()
                .isAcknowledged(),
            equalTo(true)
        );

        createSnapshot("test-repo", "test-snap", Collections.emptyList());
        assertThat(getSnapshot("test-repo", "test-snap").state(), equalTo(SnapshotState.SUCCESS));

        logger.info("-->  delete test template");
        assertThat(client().admin().indices().prepareDeleteTemplate("test-template").get().isAcknowledged(), equalTo(true));
        GetIndexTemplatesResponse getIndexTemplatesResponse = client().admin().indices().prepareGetTemplates().get();
        assertIndexTemplateMissing(getIndexTemplatesResponse, "test-template");

        logger.info("--> restore cluster state");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), equalTo(0));

        logger.info("--> check that template is restored");
        getIndexTemplatesResponse = client().admin().indices().prepareGetTemplates().get();
        assertIndexTemplateExists(getIndexTemplatesResponse, "test-template");
    }

    public void testRenameOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        assertAcked(
            client.admin()
                .indices()
                .prepareAliases()
                .addAlias("test-idx-1", "alias-1", false)
                .addAlias("test-idx-2", "alias-2", false)
                .addAlias("test-idx-3", "alias-3", false)
        );

        indexRandomDocs("test-idx-1", 100);
        indexRandomDocs("test-idx-2", 100);

        createSnapshot("test-repo", "test-snap", Arrays.asList("test-idx-1", "test-idx-2"));

        logger.info("--> restore indices with different names");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        assertDocCount("test-idx-1-copy", 100L);
        assertDocCount("test-idx-2-copy", 100L);

        logger.info("--> close just restored indices");
        client.admin().indices().prepareClose("test-idx-1-copy", "test-idx-2-copy").get();

        logger.info("--> and try to restore these indices again");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        assertDocCount("test-idx-1-copy", 100L);
        assertDocCount("test-idx-2-copy", 100L);

        logger.info("--> close indices");
        assertAcked(client.admin().indices().prepareClose("test-idx-1", "test-idx-2-copy"));

        logger.info("--> restore indices with different names");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+-2)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> delete indices");
        cluster().wipeIndices("test-idx-1", "test-idx-1-copy", "test-idx-2", "test-idx-2-copy");

        logger.info("--> try renaming indices using the same name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setRenamePattern("(.+)")
                .setRenameReplacement("same-name")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices using the same name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setRenamePattern("test-idx-2")
                .setRenameReplacement("test-idx-1")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices using invalid index name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern(".+")
                .setRenameReplacement("__WRONG__")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (InvalidIndexNameException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern(".+")
                .setRenameReplacement("alias-3")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (InvalidIndexNameException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of itself");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern("test-idx")
                .setRenameReplacement("alias")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of another restored index");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1", "test-idx-2")
                .setRenamePattern("test-idx-1")
                .setRenameReplacement("alias-2")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of itself, but don't restore aliases ");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIndices("test-idx-1")
            .setRenamePattern("test-idx")
            .setRenameReplacement("alias")
            .setWaitForCompletion(true)
            .setIncludeAliases(false)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
    }

    public void testDynamicRestoreThrottling() throws Exception {
        Client client = client();

        createRepository(
            "test-repo",
            "fs",
            Settings.builder().put("location", randomRepoPath()).put("compress", randomBoolean()).put("chunk_size", 100, ByteSizeUnit.BYTES)
        );

        createIndexWithRandomDocs("test-idx", 100);
        createSnapshot("test-repo", "test-snap", Collections.singletonList("test-idx"));

        logger.info("--> delete index");
        cluster().wipeIndices("test-idx");

        logger.info("--> restore index");
        client.admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey(), "100b").build())
            .get();
        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .execute();

        // check if throttling is active
        assertBusy(() -> {
            long restorePause = 0L;
            for (RepositoriesService repositoriesService : internalCluster().getDataNodeInstances(RepositoriesService.class)) {
                restorePause += repositoriesService.repository("test-repo").getRestoreThrottleTimeInNanos();
            }
            assertThat(restorePause, greaterThan(TimeValue.timeValueSeconds(randomIntBetween(1, 5)).nanos()));
            assertFalse(restoreSnapshotResponse.isDone());
        }, 30, TimeUnit.SECONDS);

        // run at full speed again
        client.admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().putNull(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey()).build())
            .get();

        // check that restore now completes quickly (i.e. within 20 seconds)
        assertThat(restoreSnapshotResponse.get(20L, TimeUnit.SECONDS).getRestoreInfo().totalShards(), greaterThan(0));
        assertDocCount("test-idx", 100L);
    }

    public void testChangeSettingsOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        logger.info("--> create test index with case-preserving search analyzer");

        Settings.Builder indexSettings = Settings.builder()
            .put(indexSettings())
            .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
            .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), "10s")
            .put("index.analysis.analyzer.my_analyzer.type", "custom")
            .put("index.analysis.analyzer.my_analyzer.tokenizer", "standard");

        assertAcked(prepareCreate("test-idx", 2, indexSettings));

        int numberOfShards = getNumShards("test-idx").numPrimaries;
        assertAcked(
            client().admin()
                .indices()
                .preparePutMapping("test-idx")
                .setType("_doc")
                .setSource("field1", "type=text,analyzer=standard,search_analyzer=my_analyzer")
        );
        final int numdocs = randomIntBetween(10, 100);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numdocs];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = client().prepareIndex("test-idx", "_doc").setId(Integer.toString(i)).setSource("field1", "Foo bar " + i);
        }
        indexRandom(true, builders);
        flushAndRefresh();

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), 0);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);

        createSnapshot("test-repo", "test-snap", Collections.singletonList("test-idx"));

        logger.info("--> delete the index and recreate it while changing refresh interval and analyzer");
        cluster().wipeIndices("test-idx");

        Settings newIndexSettings = Settings.builder()
            .put("refresh_interval", "5s")
            .put("index.analysis.analyzer.my_analyzer.type", "standard")
            .build();

        Settings newIncorrectIndexSettings = Settings.builder()
            .put(newIndexSettings)
            .put(SETTING_NUMBER_OF_SHARDS, numberOfShards + 100)
            .build();

        logger.info("--> try restoring while changing the number of shards - should fail");
        assertRequestBuilderThrows(
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIgnoreIndexSettings("index.analysis.*")
                .setIndexSettings(newIncorrectIndexSettings)
                .setWaitForCompletion(true),
            SnapshotRestoreException.class
        );

        logger.info("--> try restoring while changing the number of replicas to a negative number - should fail");
        Settings newIncorrectReplicasIndexSettings = Settings.builder()
            .put(newIndexSettings)
            .put(SETTING_NUMBER_OF_REPLICAS.substring(IndexMetadata.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, -1))
            .build();
        assertRequestBuilderThrows(
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIgnoreIndexSettings("index.analysis.*")
                .setIndexSettings(newIncorrectReplicasIndexSettings)
                .setWaitForCompletion(true),
            IllegalArgumentException.class
        );

        logger.info("--> restore index with correct settings from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIgnoreIndexSettings("index.analysis.*")
            .setIndexSettings(newIndexSettings)
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that correct settings are restored");
        GetSettingsResponse getSettingsResponse = client.admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", INDEX_REFRESH_INTERVAL_SETTING.getKey()), equalTo("5s"));
        // Make sure that number of shards didn't change
        assertThat(getSettingsResponse.getSetting("test-idx", SETTING_NUMBER_OF_SHARDS), equalTo("" + numberOfShards));
        assertThat(getSettingsResponse.getSetting("test-idx", "index.analysis.analyzer.my_analyzer.type"), equalTo("standard"));

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);

        logger.info("--> delete the index and recreate it while deleting all index settings");
        cluster().wipeIndices("test-idx");

        logger.info("--> restore index with correct settings from the snapshot");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIgnoreIndexSettings("*") // delete everything we can delete
            .setIndexSettings(newIndexSettings)
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that correct settings are restored and index is still functional");
        getSettingsResponse = client.admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", INDEX_REFRESH_INTERVAL_SETTING.getKey()), equalTo("5s"));
        // Make sure that number of shards didn't change
        assertThat(getSettingsResponse.getSetting("test-idx", SETTING_NUMBER_OF_SHARDS), equalTo("" + numberOfShards));

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);
    }

    public void testRecreateBlocksOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        Settings.Builder indexSettings = Settings.builder()
            .put(indexSettings())
            .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
            .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), "10s");

        logger.info("--> create index");
        assertAcked(prepareCreate("test-idx", 2, indexSettings));

        try {
            List<String> initialBlockSettings = randomSubsetOf(
                randomInt(3),
                IndexMetadata.SETTING_BLOCKS_WRITE,
                IndexMetadata.SETTING_BLOCKS_METADATA,
                IndexMetadata.SETTING_READ_ONLY
            );
            Settings.Builder initialSettingsBuilder = Settings.builder();
            for (String blockSetting : initialBlockSettings) {
                initialSettingsBuilder.put(blockSetting, true);
            }
            Settings initialSettings = initialSettingsBuilder.build();
            logger.info("--> using initial block settings {}", initialSettings);

            if (initialSettings.isEmpty() == false) {
                logger.info("--> apply initial blocks to index");
                client().admin().indices().prepareUpdateSettings("test-idx").setSettings(initialSettingsBuilder).get();
            }

            createSnapshot("test-repo", "test-snap", Collections.singletonList("test-idx"));

            logger.info("--> remove blocks and delete index");
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_METADATA);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_READ_ONLY);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_WRITE);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_READ);
            cluster().wipeIndices("test-idx");

            logger.info("--> restore index with additional block changes");
            List<String> changeBlockSettings = randomSubsetOf(
                randomInt(4),
                IndexMetadata.SETTING_BLOCKS_METADATA,
                IndexMetadata.SETTING_BLOCKS_WRITE,
                IndexMetadata.SETTING_READ_ONLY,
                IndexMetadata.SETTING_BLOCKS_READ
            );
            Settings.Builder changedSettingsBuilder = Settings.builder();
            for (String blockSetting : changeBlockSettings) {
                changedSettingsBuilder.put(blockSetting, randomBoolean());
            }
            Settings changedSettings = changedSettingsBuilder.build();
            logger.info("--> applying changed block settings {}", changedSettings);

            RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndexSettings(changedSettings)
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

            ClusterBlocks blocks = client.admin().cluster().prepareState().clear().setBlocks(true).get().getState().blocks();
            // compute current index settings (as we cannot query them if they contain SETTING_BLOCKS_METADATA)
            Settings mergedSettings = Settings.builder().put(initialSettings).put(changedSettings).build();
            logger.info("--> merged block settings {}", mergedSettings);

            logger.info("--> checking consistency between settings and blocks");
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_METADATA, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_METADATA_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_READ, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_READ_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_WRITE, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_WRITE_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_READ_ONLY, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_READ_ONLY_BLOCK))
            );
        } finally {
            logger.info("--> cleaning up blocks");
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_METADATA);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_READ_ONLY);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_WRITE);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_READ);
        }
    }

    public void testForbidDisableSoftDeletesDuringRestore() throws Exception {
        createRepository("test-repo", "fs");
        final Settings.Builder settings = Settings.builder();
        if (randomBoolean()) {
            settings.put(INDEX_SOFT_DELETES_SETTING.getKey(), true);
        }
        createIndex("test-index", settings.build());
        ensureGreen();
        if (randomBoolean()) {
            indexRandomDocs("test-index", between(0, 100));
            flush("test-index");
        }
        createSnapshot("test-repo", "snapshot-0", Collections.singletonList("test-index"));
        final SnapshotRestoreException restoreError = expectThrows(
            SnapshotRestoreException.class,
            () -> clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                .setIndexSettings(Settings.builder().put(INDEX_SOFT_DELETES_SETTING.getKey(), false))
                .setRenamePattern("test-index")
                .setRenameReplacement("new-index")
                .get()
        );
        assertThat(restoreError.getMessage(), containsString("cannot disable setting [index.soft_deletes.enabled] on restore"));
    }

    public void testFailOnAncientVersion() throws Exception {
        final String repoName = "test-repo";
        final Path repoPath = randomRepoPath();
        createRepository(repoName, FsRepository.TYPE, repoPath);
        final Version oldVersion = Version.CURRENT.minimumIndexCompatibilityVersion().minimumCompatibilityVersion();
        final String oldSnapshot = initWithSnapshotVersion(repoName, repoPath, oldVersion);
        final SnapshotRestoreException snapshotRestoreException = expectThrows(
            SnapshotRestoreException.class,
            () -> client().admin().cluster().prepareRestoreSnapshot(repoName, oldSnapshot).execute().actionGet()
        );
        assertThat(
            snapshotRestoreException.getMessage(),
            containsString(
                "the snapshot was created with Elasticsearch version ["
                    + oldVersion
                    + "] which is below the current versions minimum index compatibility version ["
                    + Version.CURRENT.minimumIndexCompatibilityVersion()
                    + "]"
            )
        );
    }

    public void testNoWarningsOnRestoreOverClosedIndex() throws IllegalAccessException {
        final String repoName = "test-repo";
        createRepository(repoName, FsRepository.TYPE);
        final String indexName = "test-idx";
        createIndexWithContent(indexName);
        final String snapshotName = "test-snapshot";
        createSnapshot(repoName, snapshotName, org.elasticsearch.core.List.of(indexName));
        index(indexName, "_doc", "some_id", org.elasticsearch.core.Map.of("foo", "bar"));
        assertAcked(admin().indices().prepareClose(indexName).get());
        final MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.addExpectation(
            new MockLogAppender.UnseenEventExpectation("no warnings", FileRestoreContext.class.getCanonicalName(), Level.WARN, "*")
        );
        mockAppender.start();
        final Logger logger = LogManager.getLogger(FileRestoreContext.class);
        Loggers.addAppender(logger, mockAppender);
        try {
            final RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot(repoName, snapshotName)
                .setIndices(indexName)
                .setRestoreGlobalState(false)
                .setWaitForCompletion(true)
                .get();
            assertEquals(0, restoreSnapshotResponse.getRestoreInfo().failedShards());
            mockAppender.assertAllExpectationsMatched();
        } finally {
            Loggers.removeAppender(logger, mockAppender);
            mockAppender.stop();
        }
    }
}
