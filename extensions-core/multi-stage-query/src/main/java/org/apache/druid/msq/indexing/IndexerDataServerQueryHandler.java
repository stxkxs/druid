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

package org.apache.druid.msq.indexing;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.druid.client.ImmutableSegmentLoadInfo;
import org.apache.druid.client.coordinator.CoordinatorClient;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.discovery.DataServerClient;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.RetryUtils;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.guava.Yielder;
import org.apache.druid.java.util.common.guava.Yielders;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.msq.counters.ChannelCounters;
import org.apache.druid.msq.exec.DataServerQueryHandler;
import org.apache.druid.msq.exec.DataServerQueryHandlerUtils;
import org.apache.druid.msq.exec.DataServerQueryResult;
import org.apache.druid.msq.exec.SegmentSource;
import org.apache.druid.msq.input.table.DataServerRequestDescriptor;
import org.apache.druid.msq.input.table.DataServerSelector;
import org.apache.druid.msq.input.table.RichSegmentDescriptor;
import org.apache.druid.query.Queries;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryInterruptedException;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.QueryToolChestWarehouse;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.aggregation.MetricManipulatorFns;
import org.apache.druid.query.context.DefaultResponseContext;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.rpc.RpcException;
import org.apache.druid.rpc.ServiceClientFactory;
import org.apache.druid.rpc.ServiceLocation;
import org.apache.druid.server.coordination.DruidServerMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task implementation of {@link DataServerQueryHandler}. Implements retry logic as described in
 * {@link #fetchRowsFromDataServer(Query, Function, Closer)}.
 */
public class IndexerDataServerQueryHandler implements DataServerQueryHandler
{
  private static final Logger log = new Logger(IndexerDataServerQueryHandler.class);
  private static final int DEFAULT_NUM_TRIES = 3;
  private static final int PER_SERVER_QUERY_NUM_TRIES = 5;
  private final int inputNumber;
  private final String dataSourceName;
  private final ChannelCounters channelCounters;
  private final ServiceClientFactory serviceClientFactory;
  private final CoordinatorClient coordinatorClient;
  private final ObjectMapper objectMapper;
  private final QueryToolChestWarehouse warehouse;
  private final DataServerRequestDescriptor dataServerRequestDescriptor;

  public IndexerDataServerQueryHandler(
      int inputNumber,
      String dataSourceName,
      ChannelCounters channelCounters,
      ServiceClientFactory serviceClientFactory,
      CoordinatorClient coordinatorClient,
      ObjectMapper objectMapper,
      QueryToolChestWarehouse warehouse,
      DataServerRequestDescriptor dataServerRequestDescriptor
  )
  {
    this.inputNumber = inputNumber;
    this.dataSourceName = dataSourceName;
    this.channelCounters = channelCounters;
    this.serviceClientFactory = serviceClientFactory;
    this.coordinatorClient = coordinatorClient;
    this.objectMapper = objectMapper;
    this.warehouse = warehouse;
    this.dataServerRequestDescriptor = dataServerRequestDescriptor;
  }

  @VisibleForTesting
  DataServerClient makeDataServerClient(ServiceLocation serviceLocation)
  {
    return new DataServerClient(serviceClientFactory, serviceLocation, objectMapper);
  }

  /**
   * {@inheritDoc}
   *
   * This method blocks until servers start sending their results back. When the method returns, the future is
   * immediately resolved. This means that on tasks, queries to realtime servers block the processing pool.
   * In principle, however, it should be possible to make the logic in this class asynchronous.
   *
   * Queries are retried if needed. If a data server indicates that some segments were not found, this function checks
   * with the coordinator to see if the segment was handed off.
   * <ul>
   * <li>If all the segments were handed off, returns a {@link DataServerQueryResult} with the yielder and list of
   * handed-off segments.</li>
   * <li>If some segments were not handed off, checks with the coordinator to fetch an updated list of servers.
   * This step is repeated up to {@link #DEFAULT_NUM_TRIES} times.</li>
   * <li>If the servers could not be found, checks if the segment was handed-off. If it was, returns a
   * {@link DataServerQueryResult} with the yielder and list of handed off segments. Otherwise,
   * throws an exception.</li>
   * </ul>
   *
   * @param <QueryType> result return type for the query from the data server
   * @param <RowType>   type of the result rows after parsing from QueryType object
   */
  @Override
  public <RowType, QueryType> ListenableFuture<DataServerQueryResult<RowType>> fetchRowsFromDataServer(
      Query<QueryType> query,
      Function<Sequence<QueryType>, Sequence<RowType>> mappingFunction,
      Closer closer
  )
  {
    final Query<QueryType> preparedQuery = DataServerQueryHandlerUtils.prepareQuery(query, inputNumber, dataSourceName);
    final List<Yielder<RowType>> yielders = new ArrayList<>();
    final List<RichSegmentDescriptor> handedOffSegments = new ArrayList<>();

    List<DataServerRequestDescriptor> pendingRequests = ImmutableList.of(dataServerRequestDescriptor);

    final int maxRetries = preparedQuery.context().getNumRetriesOnMissingSegments(DEFAULT_NUM_TRIES);
    int retryCount = 0;

    while (!pendingRequests.isEmpty()) {
      final ResponseContext responseContext = new DefaultResponseContext();
      final Set<RichSegmentDescriptor> processedSegments = new HashSet<>();
      for (DataServerRequestDescriptor descriptor : pendingRequests) {
        log.info("Querying server [%s] for segments[%s]", descriptor.getServerMetadata(), descriptor.getSegments());
        processedSegments.addAll(descriptor.getSegments());
        Yielder<RowType> yielder = fetchRowsFromDataServerInternal(
            descriptor,
            responseContext,
            closer,
            preparedQuery,
            mappingFunction
        );

        // Add results
        if (yielder != null && !yielder.isDone()) {
          yielders.add(yielder);
        }
      }

      // Check for missing segments
      List<SegmentDescriptor> missingSegments = DataServerQueryHandlerUtils.getMissingSegments(responseContext);
      if (missingSegments.isEmpty()) {
        // No segments remaining.
        break;
      }

      final List<SegmentDescriptor> handedOffSegmentDescriptors = checkSegmentHandoff(missingSegments);

      Set<RichSegmentDescriptor> missingRichSegmentDescriptors = new HashSet<>();
      for (RichSegmentDescriptor richSegmentDescriptor : processedSegments) {
        SegmentDescriptor segmentDescriptor = toSegmentDescriptorWithFullInterval(richSegmentDescriptor);
        if (missingSegments.contains(segmentDescriptor)) {
          if (handedOffSegmentDescriptors.contains(segmentDescriptor)) {
            handedOffSegments.add(richSegmentDescriptor);
          } else {
            missingRichSegmentDescriptors.add(richSegmentDescriptor);
          }
        }
      }

      pendingRequests = createNextPendingRequests(
          missingRichSegmentDescriptors
      );

      if (!pendingRequests.isEmpty()) {
        retryCount++;
        if (retryCount > maxRetries) {
          throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                              .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                              .build("Unable to fetch results from dataservers in [%d] retries.", retryCount);
        }
      }
    }

    // Not actually async. The retry logic above is written in synchronous fashion. Just return an immediate-future
    // when we actually have all queries issued and all yielders set up.
    return Futures.immediateFuture(new DataServerQueryResult<>(yielders, handedOffSegments, dataSourceName));
  }

  private <QueryType, RowType> Yielder<RowType> fetchRowsFromDataServerInternal(
      final DataServerRequestDescriptor requestDescriptor,
      final ResponseContext responseContext,
      final Closer closer,
      final Query<QueryType> query,
      final Function<Sequence<QueryType>, Sequence<RowType>> mappingFunction
  )
  {
    final ServiceLocation serviceLocation = ServiceLocation.fromDruidServerMetadata(requestDescriptor.getServerMetadata());
    final DataServerClient dataServerClient = makeDataServerClient(serviceLocation);
    final QueryToolChest<QueryType, Query<QueryType>> toolChest = warehouse.getToolChest(query);
    final Function<QueryType, QueryType> preComputeManipulatorFn =
        toolChest.makePreComputeManipulatorFn(query, MetricManipulatorFns.deserializing());
    final JavaType queryResultType = toolChest.getBaseResultType();
    final List<SegmentDescriptor> segmentDescriptors =
        requestDescriptor.getSegments()
                         .stream()
                         .map(IndexerDataServerQueryHandler::toSegmentDescriptorWithFullInterval)
                         .collect(Collectors.toList());

    try {
      return RetryUtils.retry(
          () -> {
            final ListenableFuture<Sequence<QueryType>> queryFuture = dataServerClient.run(
                Queries.withSpecificSegments(
                    query,
                    requestDescriptor.getSegments()
                                     .stream()
                                     .map(RichSegmentDescriptor::toPlainDescriptor)
                                     .collect(Collectors.toList())
                ),
                responseContext,
                queryResultType,
                closer
            );

            return closer.register(
                DataServerQueryHandlerUtils.createYielder(
                    queryFuture.get().map(preComputeManipulatorFn),
                    mappingFunction,
                    channelCounters
                )
            );
          },
          throwable -> !(throwable instanceof ExecutionException
                         && throwable.getCause() instanceof QueryInterruptedException
                         && throwable.getCause().getCause() instanceof InterruptedException),
          PER_SERVER_QUERY_NUM_TRIES
      );
    }
    catch (ExecutionException e) {
      if (e.getCause() instanceof QueryInterruptedException && e.getCause().getCause() instanceof RpcException) {
        // In the case that all the realtime servers for a segment are gone (for example, if they were scaled down),
        // we would also be unable to fetch the segment.
        responseContext.addMissingSegments(segmentDescriptors);
        return Yielders.each(Sequences.empty());
      } else {
        throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                            .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                            .build(e, "Exception while fetching rows for query from dataservers[%s]", serviceLocation);
      }
    }
    catch (Exception e) {
      throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                          .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                          .build(e, "Exception while fetching rows for query from dataservers[%s]", serviceLocation);
    }
  }

  private List<DataServerRequestDescriptor> createNextPendingRequests(
      final Set<RichSegmentDescriptor> richSegmentDescriptors
  )
  {
    final Map<DruidServerMetadata, Set<RichSegmentDescriptor>> serverVsSegmentsMap = new HashMap<>();

    Iterable<ImmutableSegmentLoadInfo> immutableSegmentLoadInfos =
        coordinatorClient.fetchServerViewSegments(
            dataSourceName,
            richSegmentDescriptors.stream().map(RichSegmentDescriptor::getFullInterval).collect(Collectors.toList())
        );

    Map<SegmentDescriptor, ImmutableSegmentLoadInfo> segmentVsServerMap = new HashMap<>();
    immutableSegmentLoadInfos.forEach(immutableSegmentLoadInfo -> {
      segmentVsServerMap.put(immutableSegmentLoadInfo.getSegment().toDescriptor(), immutableSegmentLoadInfo);
    });

    for (RichSegmentDescriptor richSegmentDescriptor : richSegmentDescriptors) {
      SegmentDescriptor segmentDescriptorWithFullInterval = toSegmentDescriptorWithFullInterval(richSegmentDescriptor);
      if (!segmentVsServerMap.containsKey(segmentDescriptorWithFullInterval)) {
        throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                            .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                            .build("Could not find a server for segment[%s]", richSegmentDescriptor);
      }

      ImmutableSegmentLoadInfo segmentLoadInfo = segmentVsServerMap.get(segmentDescriptorWithFullInterval);
      if (segmentLoadInfo.getSegment().toDescriptor().equals(segmentDescriptorWithFullInterval)) {
        Set<DruidServerMetadata> servers = segmentLoadInfo.getServers()
                                                          .stream()
                                                          .filter(druidServerMetadata -> SegmentSource.REALTIME.getUsedServerTypes()
                                                                                                               .contains(
                                                                                                                   druidServerMetadata.getType()))
                                                          .collect(Collectors.toSet());
        if (servers.isEmpty()) {
          throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                              .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                              .build(
                                  "Could not find a server matching includeSegmentSource[%s] for segment[%s]. Only found servers [%s]",
                                  SegmentSource.REALTIME, richSegmentDescriptor, servers
                              );
        }

        DruidServerMetadata druidServerMetadata = DataServerSelector.RANDOM.getSelectServerFunction().apply(servers);
        serverVsSegmentsMap.computeIfAbsent(druidServerMetadata, ignored -> new HashSet<>());
        SegmentDescriptor descriptor = segmentLoadInfo.getSegment().toDescriptor();
        serverVsSegmentsMap.get(druidServerMetadata)
                           .add(new RichSegmentDescriptor(
                               richSegmentDescriptor.getFullInterval(),
                               richSegmentDescriptor.getInterval(),
                               descriptor.getVersion(),
                               descriptor.getPartitionNumber()
                           ));
      }
    }

    final List<DataServerRequestDescriptor> requestDescriptors = new ArrayList<>();
    for (Map.Entry<DruidServerMetadata, Set<RichSegmentDescriptor>> druidServerMetadataSetEntry : serverVsSegmentsMap.entrySet()) {
      DataServerRequestDescriptor dataServerRequest = new DataServerRequestDescriptor(
          druidServerMetadataSetEntry.getKey(),
          ImmutableList.copyOf(druidServerMetadataSetEntry.getValue())
      );
      requestDescriptors.add(dataServerRequest);
    }

    return requestDescriptors;
  }

  /**
   * Queries the coordinator to check if a list of segments has been handed off.
   * Returns a list of segments which have been handed off.
   * <br>
   * See {@link  org.apache.druid.server.http.DataSourcesResource#isHandOffComplete(String, String, int, String)}
   */
  private List<SegmentDescriptor> checkSegmentHandoff(List<SegmentDescriptor> segmentDescriptors)
  {
    try {
      List<SegmentDescriptor> handedOffSegments = new ArrayList<>();

      for (SegmentDescriptor segmentDescriptor : segmentDescriptors) {
        Boolean wasHandedOff = FutureUtils.get(
            coordinatorClient.isHandoffComplete(dataSourceName, segmentDescriptor),
            true
        );
        if (Boolean.TRUE.equals(wasHandedOff)) {
          handedOffSegments.add(segmentDescriptor);
        }
      }
      return handedOffSegments;
    }
    catch (Exception e) {
      throw DruidException.forPersona(DruidException.Persona.OPERATOR)
                          .ofCategory(DruidException.Category.RUNTIME_FAILURE)
                          .build(e, "Could not contact coordinator");
    }
  }

  static SegmentDescriptor toSegmentDescriptorWithFullInterval(RichSegmentDescriptor richSegmentDescriptor)
  {
    return new SegmentDescriptor(
        richSegmentDescriptor.getFullInterval(),
        richSegmentDescriptor.getVersion(),
        richSegmentDescriptor.getPartitionNumber()
    );
  }
}
