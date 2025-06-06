/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.client.cache.CachePopulatorStats;
import org.apache.druid.client.cache.ForegroundCachePopulator;
import org.apache.druid.client.cache.LocalCacheProvider;
import org.apache.druid.error.DruidException;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.MapUtils;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.guava.Yielder;
import org.apache.druid.java.util.common.guava.YieldingAccumulator;
import org.apache.druid.java.util.common.guava.YieldingSequenceBase;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.ConcatQueryRunner;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.DefaultQueryMetrics;
import org.apache.druid.query.DefaultQueryRunnerFactoryConglomerate;
import org.apache.druid.query.Druids;
import org.apache.druid.query.ForwardingQueryProcessingPool;
import org.apache.druid.query.NoopQueryRunner;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryProcessingPool;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.QueryUnsupportedException;
import org.apache.druid.query.Result;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.aggregation.MetricManipulationFn;
import org.apache.druid.query.context.DefaultResponseContext;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.filter.DimFilter;
import org.apache.druid.query.planning.ExecutionVertex;
import org.apache.druid.query.search.SearchQuery;
import org.apache.druid.query.search.SearchResultValue;
import org.apache.druid.query.spec.MultipleSpecificSegmentSpec;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.ReferenceCountingSegment;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.TestIndex;
import org.apache.druid.segment.loading.LeastBytesUsedStorageLocationSelectorStrategy;
import org.apache.druid.segment.loading.SegmentLoaderConfig;
import org.apache.druid.segment.loading.SegmentLoadingException;
import org.apache.druid.segment.loading.SegmentLocalCacheManager;
import org.apache.druid.segment.loading.StorageLocation;
import org.apache.druid.segment.loading.StorageLocationConfig;
import org.apache.druid.segment.loading.TombstoneSegmentizerFactory;
import org.apache.druid.server.SegmentManager;
import org.apache.druid.server.TestSegmentUtils;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.apache.druid.timeline.partition.TombstoneShardSpec;
import org.hamcrest.MatcherAssert;
import org.hamcrest.text.StringContainsInOrder;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ServerManagerTest
{
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private ServerManager serverManager;
  private MyQueryRunnerFactory factory;
  private CountDownLatch queryWaitLatch;
  private CountDownLatch queryWaitYieldLatch;
  private CountDownLatch queryNotifyLatch;
  private ExecutorService serverManagerExec;
  private SegmentManager segmentManager;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void setUp()
  {
    final SegmentLoaderConfig loaderConfig = new SegmentLoaderConfig()
    {
      @Override
      public File getInfoDir()
      {
        return temporaryFolder.getRoot();
      }

      @Override
      public List<StorageLocationConfig> getLocations()
      {
        return Collections.singletonList(
            new StorageLocationConfig(temporaryFolder.getRoot(), null, null)
        );
      }
    };

    final List<StorageLocation> storageLocations = loaderConfig.toStorageLocations();
    final SegmentLocalCacheManager localCacheManager = new SegmentLocalCacheManager(
        storageLocations,
        loaderConfig,
        new LeastBytesUsedStorageLocationSelectorStrategy(storageLocations),
        TestIndex.INDEX_IO,
        TestHelper.makeJsonMapper()
    )
    {
      @Override
      public ReferenceCountingSegment getSegment(final DataSegment dataSegment)
      {
        if (dataSegment.isTombstone()) {
          return ReferenceCountingSegment
              .wrapSegment(TombstoneSegmentizerFactory.segmentForTombstone(dataSegment), dataSegment.getShardSpec());
        } else {
          return ReferenceCountingSegment.wrapSegment(new TestSegmentUtils.SegmentForTesting(
              dataSegment.getDataSource(),
              (Interval) dataSegment.getLoadSpec().get("interval"),
              MapUtils.getString(dataSegment.getLoadSpec(), "version")
          ), dataSegment.getShardSpec());
        }
      }
    };

    segmentManager = new SegmentManager(localCacheManager);

    EmittingLogger.registerEmitter(new NoopServiceEmitter());
    queryWaitLatch = new CountDownLatch(1);
    queryWaitYieldLatch = new CountDownLatch(1);
    queryNotifyLatch = new CountDownLatch(1);
    factory = new MyQueryRunnerFactory(queryWaitLatch, queryWaitYieldLatch, queryNotifyLatch);
    serverManagerExec = Execs.multiThreaded(2, "ServerManagerTest-%d");
    QueryRunnerFactoryConglomerate conglomerate = DefaultQueryRunnerFactoryConglomerate.buildFromQueryRunnerFactories(ImmutableMap
        .<Class<? extends Query>, QueryRunnerFactory>builder()
        .put(SearchQuery.class, factory)
        .build());
    serverManager = new ServerManager(
        conglomerate,
        new NoopServiceEmitter(),
        new ForwardingQueryProcessingPool(serverManagerExec),
        new ForegroundCachePopulator(new DefaultObjectMapper(), new CachePopulatorStats(), -1),
        new DefaultObjectMapper(),
        new LocalCacheProvider().get(),
        new CacheConfig(),
        segmentManager,
        new ServerConfig()
    );

    loadQueryable("test", "1", Intervals.of("P1d/2011-04-01"));
    loadQueryable("test", "1", Intervals.of("P1d/2011-04-02"));
    loadQueryable("test", "2", Intervals.of("P1d/2011-04-02"));
    loadQueryable("test", "1", Intervals.of("P1d/2011-04-03"));
    loadQueryable("test", "1", Intervals.of("P1d/2011-04-04"));
    loadQueryable("test", "1", Intervals.of("P1d/2011-04-05"));
    loadQueryable("test", "2", Intervals.of("PT1h/2011-04-04T01"));
    loadQueryable("test", "2", Intervals.of("PT1h/2011-04-04T02"));
    loadQueryable("test", "2", Intervals.of("PT1h/2011-04-04T03"));
    loadQueryable("test", "2", Intervals.of("PT1h/2011-04-04T05"));
    loadQueryable("test", "2", Intervals.of("PT1h/2011-04-04T06"));
    loadQueryable("test2", "1", Intervals.of("P1d/2011-04-01"));
    loadQueryable("test2", "1", Intervals.of("P1d/2011-04-02"));
    loadQueryable("testTombstone", "1", Intervals.of("P1d/2011-04-02"));
  }

  @Test
  public void testSimpleGet()
  {
    Future future = assertQueryable(
        Granularities.DAY,
        "test",
        Intervals.of("P1d/2011-04-01"),
        ImmutableList.of(
            new Pair<>("1", Intervals.of("P1d/2011-04-01"))
        )
    );
    waitForTestVerificationAndCleanup(future);

    future = assertQueryable(
        Granularities.DAY,
        "test", Intervals.of("P2d/2011-04-02"),
        ImmutableList.of(
            new Pair<>("1", Intervals.of("P1d/2011-04-01")),
            new Pair<>("2", Intervals.of("P1d/2011-04-02"))
        )
    );
    waitForTestVerificationAndCleanup(future);
  }

  @Test
  public void testSimpleGetTombstone()
  {
    Future future = assertQueryable(
        Granularities.DAY,
        "testTombstone",
        Intervals.of("P1d/2011-04-01"),
        Collections.emptyList() // tombstone returns no data
    );
    waitForTestVerificationAndCleanup(future);

  }

  @Test
  public void testDelete1()
  {
    final String dataSouce = "test";
    final Interval interval = Intervals.of("2011-04-01/2011-04-02");

    Future future = assertQueryable(
        Granularities.DAY,
        dataSouce, interval,
        ImmutableList.of(
            new Pair<>("2", interval)
        )
    );
    waitForTestVerificationAndCleanup(future);

    dropQueryable(dataSouce, "2", interval);
    future = assertQueryable(
        Granularities.DAY,
        dataSouce, interval,
        ImmutableList.of(
            new Pair<>("1", interval)
        )
    );
    waitForTestVerificationAndCleanup(future);
  }

  @Test
  public void testDelete2()
  {
    loadQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    Future future = assertQueryable(
        Granularities.DAY,
        "test", Intervals.of("2011-04-04/2011-04-06"),
        ImmutableList.of(
            new Pair<>("3", Intervals.of("2011-04-04/2011-04-05"))
        )
    );
    waitForTestVerificationAndCleanup(future);

    dropQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));
    dropQueryable("test", "1", Intervals.of("2011-04-04/2011-04-05"));

    future = assertQueryable(
        Granularities.HOUR,
        "test", Intervals.of("2011-04-04/2011-04-04T06"),
        ImmutableList.of(
            new Pair<>("2", Intervals.of("2011-04-04T00/2011-04-04T01")),
            new Pair<>("2", Intervals.of("2011-04-04T01/2011-04-04T02")),
            new Pair<>("2", Intervals.of("2011-04-04T02/2011-04-04T03")),
            new Pair<>("2", Intervals.of("2011-04-04T04/2011-04-04T05")),
            new Pair<>("2", Intervals.of("2011-04-04T05/2011-04-04T06"))
        )
    );
    waitForTestVerificationAndCleanup(future);

    future = assertQueryable(
        Granularities.HOUR,
        "test", Intervals.of("2011-04-04/2011-04-04T03"),
        ImmutableList.of(
            new Pair<>("2", Intervals.of("2011-04-04T00/2011-04-04T01")),
            new Pair<>("2", Intervals.of("2011-04-04T01/2011-04-04T02")),
            new Pair<>("2", Intervals.of("2011-04-04T02/2011-04-04T03"))
        )
    );
    waitForTestVerificationAndCleanup(future);

    future = assertQueryable(
        Granularities.HOUR,
        "test", Intervals.of("2011-04-04T04/2011-04-04T06"),
        ImmutableList.of(
            new Pair<>("2", Intervals.of("2011-04-04T04/2011-04-04T05")),
            new Pair<>("2", Intervals.of("2011-04-04T05/2011-04-04T06"))
        )
    );
    waitForTestVerificationAndCleanup(future);
  }

  @Test
  public void testReferenceCounting() throws Exception
  {
    loadQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    Future future = assertQueryable(
        Granularities.DAY,
        "test", Intervals.of("2011-04-04/2011-04-06"),
        ImmutableList.of(
            new Pair<>("3", Intervals.of("2011-04-04/2011-04-05"))
        )
    );

    queryNotifyLatch.await(1000, TimeUnit.MILLISECONDS);

    Assert.assertEquals(1, factory.getSegmentReferences().size());

    for (ReferenceCountingSegment referenceCountingSegment : factory.getSegmentReferences()) {
      Assert.assertEquals(1, referenceCountingSegment.getNumReferences());
    }

    queryWaitYieldLatch.countDown();

    Assert.assertEquals(1, factory.getAdapters().size());

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertFalse(segment.isClosed());
    }

    queryWaitLatch.countDown();
    future.get();

    dropQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertTrue(segment.isClosed());
    }
  }

  @Test
  public void testReferenceCountingWhileQueryExecuting() throws Exception
  {
    loadQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    Future future = assertQueryable(
        Granularities.DAY,
        "test", Intervals.of("2011-04-04/2011-04-06"),
        ImmutableList.of(
            new Pair<>("3", Intervals.of("2011-04-04/2011-04-05"))
        )
    );

    queryNotifyLatch.await(1000, TimeUnit.MILLISECONDS);

    Assert.assertEquals(1, factory.getSegmentReferences().size());

    for (ReferenceCountingSegment referenceCountingSegment : factory.getSegmentReferences()) {
      Assert.assertEquals(1, referenceCountingSegment.getNumReferences());
    }

    queryWaitYieldLatch.countDown();

    Assert.assertEquals(1, factory.getAdapters().size());

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertFalse(segment.isClosed());
    }

    dropQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertFalse(segment.isClosed());
    }

    queryWaitLatch.countDown();
    future.get();

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertTrue(segment.isClosed());
    }
  }

  @Test
  public void testMultipleDrops() throws Exception
  {
    loadQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    Future future = assertQueryable(
        Granularities.DAY,
        "test", Intervals.of("2011-04-04/2011-04-06"),
        ImmutableList.of(
            new Pair<>("3", Intervals.of("2011-04-04/2011-04-05"))
        )
    );

    queryNotifyLatch.await(1000, TimeUnit.MILLISECONDS);

    Assert.assertEquals(1, factory.getSegmentReferences().size());

    for (ReferenceCountingSegment referenceCountingSegment : factory.getSegmentReferences()) {
      Assert.assertEquals(1, referenceCountingSegment.getNumReferences());
    }

    queryWaitYieldLatch.countDown();

    Assert.assertEquals(1, factory.getAdapters().size());

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertFalse(segment.isClosed());
    }

    dropQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));
    dropQueryable("test", "3", Intervals.of("2011-04-04/2011-04-05"));

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertFalse(segment.isClosed());
    }

    queryWaitLatch.countDown();
    future.get();

    for (TestSegmentUtils.SegmentForTesting segment : factory.getAdapters()) {
      Assert.assertTrue(segment.isClosed());
    }
  }

  @Test
  public void testGetQueryRunnerForIntervalsWhenTimelineIsMissingReturningNoopQueryRunner()
  {
    final Interval interval = Intervals.of("0000-01-01/P1D");
    final QueryRunner<Result<SearchResultValue>> queryRunner = serverManager.getQueryRunnerForIntervals(
        searchQuery("unknown_datasource", interval, Granularities.ALL),
        Collections.singletonList(interval)
    );
    Assert.assertSame(NoopQueryRunner.class, queryRunner.getClass());
  }

  @Test
  public void testGetQueryRunnerForSegmentsWhenTimelineIsMissingReportingMissingSegmentsOnQueryDataSource()
  {
    final Interval interval = Intervals.of("0000-01-01/P1D");
    final SearchQuery query = searchQueryWithQueryDataSource("unknown_datasource", interval, Granularities.ALL);
    final List<SegmentDescriptor> unknownSegments = Collections.singletonList(
        new SegmentDescriptor(interval, "unknown_version", 0)
    );
    DruidException e = assertThrows(
        DruidException.class,
        () -> serverManager.getQueryRunnerForSegments(query, unknownSegments)
    );
    MatcherAssert.assertThat(
        e.getMessage(),
        StringContainsInOrder.stringContainsInOrder(Arrays.asList("Base dataSource", "is not a table!"))
    );
  }

  @Test
  public void testGetQueryRunnerForSegmentsWhenTimelineIsMissingReportingMissingSegments()
  {
    final Interval interval = Intervals.of("0000-01-01/P1D");
    final SearchQuery query = searchQuery("unknown_datasource", interval, Granularities.ALL);
    final List<SegmentDescriptor> unknownSegments = Collections.singletonList(
        new SegmentDescriptor(interval, "unknown_version", 0)
    );
    final QueryRunner<Result<SearchResultValue>> queryRunner = serverManager.getQueryRunnerForSegments(
        query,
        unknownSegments
    );
    final ResponseContext responseContext = DefaultResponseContext.createEmpty();
    final List<Result<SearchResultValue>> results = queryRunner.run(QueryPlus.wrap(query), responseContext).toList();
    Assert.assertTrue(results.isEmpty());
    Assert.assertNotNull(responseContext.getMissingSegments());
    Assert.assertEquals(unknownSegments, responseContext.getMissingSegments());
  }

  @Test
  public void testGetQueryRunnerForSegmentsWhenTimelineEntryIsMissingReportingMissingSegments()
  {
    final Interval interval = Intervals.of("P1d/2011-04-01");
    final SearchQuery query = searchQuery("test", interval, Granularities.ALL);
    final List<SegmentDescriptor> unknownSegments = Collections.singletonList(
        new SegmentDescriptor(interval, "unknown_version", 0)
    );
    final QueryRunner<Result<SearchResultValue>> queryRunner = serverManager.getQueryRunnerForSegments(
        query,
        unknownSegments
    );
    final ResponseContext responseContext = DefaultResponseContext.createEmpty();
    final List<Result<SearchResultValue>> results = queryRunner.run(QueryPlus.wrap(query), responseContext).toList();
    Assert.assertTrue(results.isEmpty());
    Assert.assertNotNull(responseContext.getMissingSegments());
    Assert.assertEquals(unknownSegments, responseContext.getMissingSegments());
  }

  @Test
  public void testGetQueryRunnerForSegmentsWhenTimelinePartitionChunkIsMissingReportingMissingSegments()
  {
    final Interval interval = Intervals.of("P1d/2011-04-01");
    final int unknownPartitionId = 1000;
    final SearchQuery query = searchQuery("test", interval, Granularities.ALL);
    final List<SegmentDescriptor> unknownSegments = Collections.singletonList(
        new SegmentDescriptor(interval, "1", unknownPartitionId)
    );
    final QueryRunner<Result<SearchResultValue>> queryRunner = serverManager.getQueryRunnerForSegments(
        query,
        unknownSegments
    );
    final ResponseContext responseContext = DefaultResponseContext.createEmpty();
    final List<Result<SearchResultValue>> results = queryRunner.run(QueryPlus.wrap(query), responseContext).toList();
    Assert.assertTrue(results.isEmpty());
    Assert.assertNotNull(responseContext.getMissingSegments());
    Assert.assertEquals(unknownSegments, responseContext.getMissingSegments());
  }

  @Test
  public void testGetQueryRunnerForSegmentsWhenSegmentIsClosedReportingMissingSegments()
  {
    final Interval interval = Intervals.of("P1d/2011-04-01");
    final SearchQuery query = searchQuery("test", interval, Granularities.ALL);
    final Optional<VersionedIntervalTimeline<String, ReferenceCountingSegment>> maybeTimeline = segmentManager
        .getTimeline(ExecutionVertex.of(query).getBaseTableDataSource());
    Assert.assertTrue(maybeTimeline.isPresent());
    final List<TimelineObjectHolder<String, ReferenceCountingSegment>> holders = maybeTimeline.get().lookup(interval);
    final List<SegmentDescriptor> closedSegments = new ArrayList<>();
    for (TimelineObjectHolder<String, ReferenceCountingSegment> holder : holders) {
      for (PartitionChunk<ReferenceCountingSegment> chunk : holder.getObject()) {
        final ReferenceCountingSegment segment = chunk.getObject();
        Assert.assertNotNull(segment.getId());
        closedSegments.add(
            new SegmentDescriptor(segment.getDataInterval(), segment.getVersion(), segment.getId().getPartitionNum())
        );
        segment.close();
      }
    }
    final QueryRunner<Result<SearchResultValue>> queryRunner = serverManager.getQueryRunnerForSegments(
        query,
        closedSegments
    );
    final ResponseContext responseContext = DefaultResponseContext.createEmpty();
    final List<Result<SearchResultValue>> results = queryRunner.run(QueryPlus.wrap(query), responseContext).toList();
    Assert.assertTrue(results.isEmpty());
    Assert.assertNotNull(responseContext.getMissingSegments());
    Assert.assertEquals(closedSegments, responseContext.getMissingSegments());
  }

  @Test
  public void testGetQueryRunnerForSegmentsForUnknownQueryThrowingException()
  {
    final Interval interval = Intervals.of("P1d/2011-04-01");
    final List<SegmentDescriptor> descriptors = Collections.singletonList(new SegmentDescriptor(interval, "1", 0));
    expectedException.expect(QueryUnsupportedException.class);
    expectedException.expectMessage("Unknown query type");
    serverManager.getQueryRunnerForSegments(
        new BaseQuery<>(
            new TableDataSource("test"),
            new MultipleSpecificSegmentSpec(descriptors),
            new HashMap<>()
        )
        {
          @Override
          public boolean hasFilters()
          {
            return false;
          }

          @Override
          public DimFilter getFilter()
          {
            return null;
          }

          @Override
          public String getType()
          {
            return null;
          }

          @Override
          public Query<Object> withOverriddenContext(Map<String, Object> contextOverride)
          {
            return this;
          }

          @Override
          public Query<Object> withQuerySegmentSpec(QuerySegmentSpec spec)
          {
            return null;
          }

          @Override
          public Query<Object> withDataSource(DataSource dataSource)
          {
            return null;
          }
        },
        descriptors
    );
  }

  private void waitForTestVerificationAndCleanup(Future future)
  {
    try {
      queryNotifyLatch.await(1000, TimeUnit.MILLISECONDS);
      queryWaitYieldLatch.countDown();
      queryWaitLatch.countDown();
      future.get();
      factory.clearAdapters();
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private SearchQuery searchQuery(String datasource, Interval interval, Granularity granularity)
  {
    return Druids.newSearchQueryBuilder()
                 .dataSource(datasource)
                 .intervals(Collections.singletonList(interval))
                 .granularity(granularity)
                 .limit(10000)
                 .query("wow")
                 .build();
  }


  private SearchQuery searchQueryWithQueryDataSource(String datasource, Interval interval, Granularity granularity)
  {
    final ImmutableList<SegmentDescriptor> descriptors = ImmutableList.of(
        new SegmentDescriptor(Intervals.of("2000/3000"), "0", 0),
        new SegmentDescriptor(Intervals.of("2000/3000"), "0", 1)
    );
    return Druids.newSearchQueryBuilder()
                 .dataSource(
                     new QueryDataSource(
                         Druids.newTimeseriesQueryBuilder()
                               .dataSource(datasource)
                               .intervals(new MultipleSpecificSegmentSpec(descriptors))
                               .granularity(Granularities.ALL)
                               .build()
                     )
                 )
                 .intervals(Collections.singletonList(interval))
                 .granularity(granularity)
                 .limit(10000)
                 .query("wow")
                 .build();
  }

  private Future assertQueryable(
      Granularity granularity,
      String dataSource,
      Interval interval,
      List<Pair<String, Interval>> expected
  )
  {
    final Iterator<Pair<String, Interval>> expectedIter = expected.iterator();
    final List<Interval> intervals = Collections.singletonList(interval);
    final SearchQuery query = searchQuery(dataSource, interval, granularity);
    final QueryRunner<Result<SearchResultValue>> runner = serverManager.getQueryRunnerForIntervals(
        query,
        intervals
    );
    return serverManagerExec.submit(
        () -> {
          Sequence<Result<SearchResultValue>> seq = runner.run(QueryPlus.wrap(query));
          seq.toList();
          Iterator<TestSegmentUtils.SegmentForTesting> adaptersIter = factory.getAdapters().iterator();

          while (expectedIter.hasNext() && adaptersIter.hasNext()) {
            Pair<String, Interval> expectedVals = expectedIter.next();
            TestSegmentUtils.SegmentForTesting value = adaptersIter.next();

            Assert.assertEquals(expectedVals.lhs, value.getVersion());
            Assert.assertEquals(expectedVals.rhs, value.getInterval());
          }

          Assert.assertFalse(expectedIter.hasNext());
          Assert.assertFalse(adaptersIter.hasNext());
        }
    );
  }

  private void loadQueryable(String dataSource, String version, Interval interval)
  {
    try {
      if ("testTombstone".equals(dataSource)) {
        segmentManager.loadSegment(
            new DataSegment(
                dataSource,
                interval,
                version,
                ImmutableMap.of("version", version,
                                "interval", interval,
                                "type",
                                DataSegment.TOMBSTONE_LOADSPEC_TYPE
                ),
                Arrays.asList("dim1", "dim2", "dim3"),
                Arrays.asList("metric1", "metric2"),
                TombstoneShardSpec.INSTANCE,
                IndexIO.CURRENT_VERSION_ID,
                1L
            )
        );
      } else {
        segmentManager.loadSegment(
            new DataSegment(
                dataSource,
                interval,
                version,
                ImmutableMap.of("version", version, "interval", interval),
                Arrays.asList("dim1", "dim2", "dim3"),
                Arrays.asList("metric1", "metric2"),
                NoneShardSpec.instance(),
                IndexIO.CURRENT_VERSION_ID,
                1L
            )
        );
      }
    }
    catch (SegmentLoadingException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void dropQueryable(String dataSource, String version, Interval interval)
  {
    segmentManager.dropSegment(
        new DataSegment(
            dataSource,
            interval,
            version,
            ImmutableMap.of("version", version, "interval", interval),
            Arrays.asList("dim1", "dim2", "dim3"),
            Arrays.asList("metric1", "metric2"),
            NoneShardSpec.instance(),
            IndexIO.CURRENT_VERSION_ID,
            123L
        )
    );
  }

  private static class MyQueryRunnerFactory implements QueryRunnerFactory<Result<SearchResultValue>, SearchQuery>
  {
    private final CountDownLatch waitLatch;
    private final CountDownLatch waitYieldLatch;
    private final CountDownLatch notifyLatch;
    private final List<TestSegmentUtils.SegmentForTesting> adapters = new ArrayList<>();
    private final List<ReferenceCountingSegment> segmentReferences = new ArrayList<>();


    public MyQueryRunnerFactory(
        CountDownLatch waitLatch,
        CountDownLatch waitYieldLatch,
        CountDownLatch notifyLatch
    )
    {
      this.waitLatch = waitLatch;
      this.waitYieldLatch = waitYieldLatch;
      this.notifyLatch = notifyLatch;
    }

    @Override
    public QueryRunner<Result<SearchResultValue>> createRunner(Segment adapter)
    {
      if (!(adapter instanceof ReferenceCountingSegment)) {
        throw new IAE("Expected instance of ReferenceCountingSegment, got %s", adapter.getClass());
      }
      final ReferenceCountingSegment segment = (ReferenceCountingSegment) adapter;

      Assert.assertTrue(segment.getNumReferences() > 0);
      segmentReferences.add(segment);
      adapters.add((TestSegmentUtils.SegmentForTesting) segment.getBaseSegment());
      return new BlockingQueryRunner<>(new NoopQueryRunner<>(), waitLatch, waitYieldLatch, notifyLatch);
    }

    @Override
    public QueryRunner<Result<SearchResultValue>> mergeRunners(
        QueryProcessingPool queryProcessingPool,
        Iterable<QueryRunner<Result<SearchResultValue>>> queryRunners
    )
    {
      return new ConcatQueryRunner<>(Sequences.simple(queryRunners));
    }

    @Override
    public QueryToolChest<Result<SearchResultValue>, SearchQuery> getToolchest()
    {
      return new NoopQueryToolChest<>();
    }

    public List<TestSegmentUtils.SegmentForTesting> getAdapters()
    {
      return adapters;
    }

    public List<ReferenceCountingSegment> getSegmentReferences()
    {
      return segmentReferences;
    }

    public void clearAdapters()
    {
      adapters.clear();
    }
  }

  public static class NoopQueryToolChest<T, QueryType extends Query<T>> extends QueryToolChest<T, QueryType>
  {
    @Override
    public QueryRunner<T> mergeResults(QueryRunner<T> runner)
    {
      return runner;
    }

    @Override
    public QueryMetrics<Query<?>> makeMetrics(QueryType query)
    {
      return new DefaultQueryMetrics<>();
    }

    @Override
    public Function<T, T> makePreComputeManipulatorFn(QueryType query, MetricManipulationFn fn)
    {
      return Functions.identity();
    }

    @Override
    public TypeReference<T> getResultTypeReference()
    {
      return new TypeReference<>() {};
    }
  }

  private static class BlockingQueryRunner<T> implements QueryRunner<T>
  {
    private final QueryRunner<T> runner;
    private final CountDownLatch waitLatch;
    private final CountDownLatch waitYieldLatch;
    private final CountDownLatch notifyLatch;

    public BlockingQueryRunner(
        QueryRunner<T> runner,
        CountDownLatch waitLatch,
        CountDownLatch waitYieldLatch,
        CountDownLatch notifyLatch
    )
    {
      this.runner = runner;
      this.waitLatch = waitLatch;
      this.waitYieldLatch = waitYieldLatch;
      this.notifyLatch = notifyLatch;
    }

    @Override
    public Sequence<T> run(QueryPlus<T> queryPlus, ResponseContext responseContext)
    {
      return new BlockingSequence<>(runner.run(queryPlus, responseContext), waitLatch, waitYieldLatch, notifyLatch);
    }
  }

  private static class BlockingSequence<T> extends YieldingSequenceBase<T>
  {
    private final Sequence<T> baseSequence;
    private final CountDownLatch waitLatch;
    private final CountDownLatch waitYieldLatch;
    private final CountDownLatch notifyLatch;


    private BlockingSequence(
        Sequence<T> baseSequence,
        CountDownLatch waitLatch,
        CountDownLatch waitYieldLatch,
        CountDownLatch notifyLatch
    )
    {
      this.baseSequence = baseSequence;
      this.waitLatch = waitLatch;
      this.waitYieldLatch = waitYieldLatch;
      this.notifyLatch = notifyLatch;
    }

    @Override
    public <OutType> Yielder<OutType> toYielder(
        final OutType initValue,
        final YieldingAccumulator<OutType, T> accumulator
    )
    {
      notifyLatch.countDown();

      try {
        waitYieldLatch.await(1000, TimeUnit.MILLISECONDS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }

      final Yielder<OutType> baseYielder = baseSequence.toYielder(initValue, accumulator);
      return new Yielder<>()
      {
        @Override
        public OutType get()
        {
          try {
            waitLatch.await(1000, TimeUnit.MILLISECONDS);
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
          return baseYielder.get();
        }

        @Override
        public Yielder<OutType> next(OutType initValue)
        {
          return baseYielder.next(initValue);
        }

        @Override
        public boolean isDone()
        {
          return baseYielder.isDone();
        }

        @Override
        public void close() throws IOException
        {
          baseYielder.close();
        }
      };
    }
  }
}
