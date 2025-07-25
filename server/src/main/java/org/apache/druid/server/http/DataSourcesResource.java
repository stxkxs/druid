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

package org.apache.druid.server.http;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import com.sun.jersey.spi.container.ResourceFilters;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.druid.audit.AuditEntry;
import org.apache.druid.audit.AuditManager;
import org.apache.druid.client.CoordinatorServerView;
import org.apache.druid.client.DataSourcesSnapshot;
import org.apache.druid.client.DruidDataSource;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.ImmutableDruidDataSource;
import org.apache.druid.client.ImmutableSegmentLoadInfo;
import org.apache.druid.client.SegmentLoadInfo;
import org.apache.druid.common.guava.FutureUtils;
import org.apache.druid.error.DruidException;
import org.apache.druid.guice.annotations.PublicApi;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.MapUtils;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.guava.Comparators;
import org.apache.druid.java.util.common.guava.FunctionalIterable;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.metadata.MetadataRuleManager;
import org.apache.druid.metadata.SegmentsMetadataManager;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.rpc.HttpResponseException;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.apache.druid.rpc.indexing.SegmentUpdateResponse;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.coordinator.DruidCoordinator;
import org.apache.druid.server.coordinator.rules.LoadRule;
import org.apache.druid.server.coordinator.rules.Rule;
import org.apache.druid.server.http.security.DatasourceResourceFilter;
import org.apache.druid.server.security.AuthorizationUtils;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.TimelineLookup;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 *
 */
@Path("/druid/coordinator/v1/datasources")
public class DataSourcesResource
{
  private static final Logger log = new Logger(DataSourcesResource.class);

  /**
   * Default offset of 14 days.
   */
  private static final long DEFAULT_LOADSTATUS_INTERVAL_OFFSET = 14 * 24 * 60 * 60 * 1000;

  private final CoordinatorServerView serverInventoryView;
  private final SegmentsMetadataManager segmentsMetadataManager;
  private final MetadataRuleManager metadataRuleManager;
  private final OverlordClient overlordClient;
  private final AuthorizerMapper authorizerMapper;
  private final DruidCoordinator coordinator;
  private final AuditManager auditManager;

  @Inject
  public DataSourcesResource(
      CoordinatorServerView serverInventoryView,
      SegmentsMetadataManager segmentsMetadataManager,
      MetadataRuleManager metadataRuleManager,
      OverlordClient overlordClient,
      AuthorizerMapper authorizerMapper,
      DruidCoordinator coordinator,
      AuditManager auditManager
  )
  {
    this.serverInventoryView = serverInventoryView;
    this.segmentsMetadataManager = segmentsMetadataManager;
    this.metadataRuleManager = metadataRuleManager;
    this.overlordClient = overlordClient;
    this.authorizerMapper = authorizerMapper;
    this.coordinator = coordinator;
    this.auditManager = auditManager;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getQueryableDataSources(
      @QueryParam("full") @Nullable String full,
      @QueryParam("simple") @Nullable String simple,
      @Context final HttpServletRequest req
  )
  {
    Response.ResponseBuilder builder = Response.ok();
    final Set<ImmutableDruidDataSource> datasources =
        InventoryViewUtils.getSecuredDataSources(req, serverInventoryView, authorizerMapper);

    final Object entity;

    if (full != null) {
      entity = datasources;
    } else if (simple != null) {
      entity = datasources.stream().map(this::makeSimpleDatasource).collect(Collectors.toList());
    } else {
      entity = datasources.stream().map(ImmutableDruidDataSource::getName).collect(Collectors.toList());
    }

    return builder.entity(entity).build();
  }

  @GET
  @Path("/{dataSourceName}")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getQueryableDataSource(
      @PathParam("dataSourceName") final String dataSourceName,
      @QueryParam("full") final String full
  )
  {
    final ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);

    if (dataSource == null) {
      return logAndCreateDataSourceNotFoundResponse(dataSourceName);
    }

    if (full != null) {
      return Response.ok(dataSource).build();
    }

    return Response.ok(getSimpleDatasource(dataSourceName)).build();
  }

  private interface RemoteSegmentUpdateOperation
  {
    ListenableFuture<SegmentUpdateResponse> perform();
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markAllNonOvershadowedSegmentsAsUsed} instead.
   */
  @Deprecated
  @POST
  @Path("/{dataSourceName}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response markAsUsedAllNonOvershadowedSegments(@PathParam("dataSourceName") final String dataSourceName)
  {
    RemoteSegmentUpdateOperation remoteOperation = () -> overlordClient
        .markNonOvershadowedSegmentsAsUsed(dataSourceName);
    return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markNonOvershadowedSegmentsAsUsed} instead.
   */
  @Deprecated
  @POST
  @Path("/{dataSourceName}/markUsed")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response markAsUsedNonOvershadowedSegments(
      @PathParam("dataSourceName") final String dataSourceName,
      final SegmentsToUpdateFilter payload
  )
  {
    if (payload == null || !payload.isValid()) {
      return Response
          .status(Response.Status.BAD_REQUEST)
          .entity(SegmentsToUpdateFilter.INVALID_PAYLOAD_ERROR_MESSAGE)
          .build();
    } else {
      RemoteSegmentUpdateOperation remoteOperation
          = () -> overlordClient.markNonOvershadowedSegmentsAsUsed(dataSourceName, payload);
      return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
    }
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markSegmentsAsUnused} instead.
   */
  @Deprecated
  @POST
  @Path("/{dataSourceName}/markUnused")
  @ResourceFilters(DatasourceResourceFilter.class)
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public Response markSegmentsAsUnused(
      @PathParam("dataSourceName") final String dataSourceName,
      final SegmentsToUpdateFilter payload,
      @Context final HttpServletRequest req
  )
  {
    if (payload == null || !payload.isValid()) {
      return Response
          .status(Response.Status.BAD_REQUEST)
          .entity(SegmentsToUpdateFilter.INVALID_PAYLOAD_ERROR_MESSAGE)
          .build();
    } else {
      RemoteSegmentUpdateOperation remoteOperation
          = () -> overlordClient.markSegmentsAsUnused(dataSourceName, payload);
      return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
    }
  }

  private static Response logAndCreateDataSourceNotFoundResponse(String dataSourceName)
  {
    log.warn("datasource[%s] not found", dataSourceName);
    return Response.noContent().build();
  }

  private static Response updateSegmentsViaOverlord(
      String dataSourceName,
      RemoteSegmentUpdateOperation operation
  )
  {
    try {
      SegmentUpdateResponse response = FutureUtils.getUnchecked(operation.perform(), true);
      return Response.ok(response).build();
    }
    catch (DruidException e) {
      return ServletResourceUtils.buildErrorResponseFrom(e);
    }
    catch (Exception e) {
      final Throwable rootCause = Throwables.getRootCause(e);
      if (rootCause instanceof HttpResponseException) {
        HttpResponseStatus status = ((HttpResponseException) rootCause).getResponse().getStatus();
        if (status.getCode() == 404) {
          final String errorMessage = "Could not update segments since Overlord is on an older version.";
          log.error(errorMessage);
          return ServletResourceUtils.buildErrorResponseFrom(
              DruidException.forPersona(DruidException.Persona.OPERATOR)
                            .ofCategory(DruidException.Category.NOT_FOUND)
                            .build(errorMessage)
          );
        }
      }

      log.error(e, "Error occurred while updating segments for datasource[%s].", dataSourceName);
      return Response
          .serverError()
          .entity(Map.of("error", "Unknown server error", "message", rootCause.toString()))
          .build();
    }
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markAllSegmentsAsUnused}
   * or {@link #killUnusedSegmentsInInterval} instead.
   */
  @DELETE
  @Deprecated
  @Path("/{dataSourceName}")
  @ResourceFilters(DatasourceResourceFilter.class)
  @Produces(MediaType.APPLICATION_JSON)
  public Response markAsUnusedAllSegmentsOrKillUnusedSegmentsInInterval(
      @PathParam("dataSourceName") final String dataSourceName,
      @QueryParam("kill") final String kill,
      @QueryParam("interval") final String interval,
      @Context HttpServletRequest req
  )
  {
    if (Boolean.parseBoolean(kill)) {
      return killUnusedSegmentsInInterval(dataSourceName, interval, req);
    } else {
      RemoteSegmentUpdateOperation remoteOperation
          = () -> overlordClient.markSegmentsAsUnused(dataSourceName);
      return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
    }
  }

  @DELETE
  @Path("/{dataSourceName}/intervals/{interval}")
  @ResourceFilters(DatasourceResourceFilter.class)
  @Produces(MediaType.APPLICATION_JSON)
  public Response killUnusedSegmentsInInterval(
      @PathParam("dataSourceName") final String dataSourceName,
      @PathParam("interval") final String interval,
      @Context final HttpServletRequest req
  )
  {
    if (StringUtils.contains(interval, '_')) {
      log.warn("Use interval with '/', not '_': [%s] given", interval);
    }
    final Interval theInterval = Intervals.of(interval.replace('_', '/'));
    try {
      final String killTaskId = FutureUtils.getUnchecked(
          overlordClient.runKillTask("api-issued", dataSourceName, theInterval, null, null, null),
          true
      );
      auditManager.doAudit(
          AuditEntry.builder()
                    .key(dataSourceName)
                    .type("segment.kill")
                    .payload(ImmutableMap.of("killTaskId", killTaskId, "interval", theInterval))
                    .auditInfo(AuthorizationUtils.buildAuditInfo(req))
                    .request(AuthorizationUtils.buildRequestInfo("coordinator", req))
                    .build()
      );
      return Response.ok().build();
    }
    catch (Exception e) {
      return Response
          .serverError()
          .entity(
              ImmutableMap.of(
                  "error", "Exception occurred. Are you sure you have an indexing service?",
                  "message", e.toString()
              )
          )
          .build();
    }
  }

  @GET
  @Path("/{dataSourceName}/intervals")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getIntervalsWithServedSegmentsOrAllServedSegmentsPerIntervals(
      @PathParam("dataSourceName") String dataSourceName,
      @QueryParam("simple") String simple,
      @QueryParam("full") String full
  )
  {
    if (simple == null && full == null) {
      final ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);
      if (dataSource == null) {
        return logAndCreateDataSourceNotFoundResponse(dataSourceName);
      }
      final Comparator<Interval> comparator = Comparators.intervalsByStartThenEnd().reversed();
      Set<Interval> intervals = new TreeSet<>(comparator);
      dataSource.getSegments().forEach(segment -> intervals.add(segment.getInterval()));
      return Response.ok(intervals).build();
    } else {
      return getServedSegmentsInInterval(dataSourceName, full != null, interval -> true);
    }
  }

  @GET
  @Path("/{dataSourceName}/intervals/{interval}")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getServedSegmentsInInterval(
      @PathParam("dataSourceName") String dataSourceName,
      @PathParam("interval") String interval,
      @QueryParam("simple") String simple,
      @QueryParam("full") String full
  )
  {
    final Interval theInterval = Intervals.of(interval.replace('_', '/'));
    if (simple == null && full == null) {
      final ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);
      if (dataSource == null) {
        return logAndCreateDataSourceNotFoundResponse(dataSourceName);
      }
      final Set<SegmentId> segmentIds = new TreeSet<>();
      for (DataSegment dataSegment : dataSource.getSegments()) {
        if (theInterval.contains(dataSegment.getInterval())) {
          segmentIds.add(dataSegment.getId());
        }
      }
      return Response.ok(segmentIds).build();
    }
    return getServedSegmentsInInterval(dataSourceName, full != null, theInterval::contains);
  }

  @GET
  @Path("/{dataSourceName}/loadstatus")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getDatasourceLoadstatus(
      @PathParam("dataSourceName") String dataSourceName,
      @QueryParam("forceMetadataRefresh") final Boolean forceMetadataRefresh,
      @QueryParam("interval") @Nullable final String interval,
      @QueryParam("simple") @Nullable final String simple,
      @QueryParam("full") @Nullable final String full,
      @QueryParam("computeUsingClusterView") @Nullable String computeUsingClusterView
  )
  {
    if (forceMetadataRefresh == null) {
      return Response
          .status(Response.Status.BAD_REQUEST)
          .entity("Invalid request. forceMetadataRefresh must be specified")
          .build();
    }
    final Interval theInterval;
    if (interval == null) {
      long currentTimeInMs = System.currentTimeMillis();
      theInterval = Intervals.utc(currentTimeInMs - DEFAULT_LOADSTATUS_INTERVAL_OFFSET, currentTimeInMs);
    } else {
      theInterval = Intervals.of(interval.replace('_', '/'));
    }

    final DataSourcesSnapshot snapshot;
    if (forceMetadataRefresh) {
      snapshot = segmentsMetadataManager.forceUpdateDataSourcesSnapshot();
    } else {
      snapshot = segmentsMetadataManager.getRecentDataSourcesSnapshot();
    }

    final ImmutableDruidDataSource immutableDruidDataSource = snapshot.getDataSource(dataSourceName);
    if (immutableDruidDataSource == null) {
      return logAndCreateDataSourceNotFoundResponse(dataSourceName);
    }

    final Set<DataSegment> segments = snapshot.getAllUsedNonOvershadowedSegments(dataSourceName, theInterval);
    if (segments.isEmpty()) {
      return Response
          .status(Response.Status.NO_CONTENT)
          .entity("No used segment found for the given datasource and interval")
          .build();
    }

    if (simple != null) {
      // Calculate response for simple mode
      SegmentsLoadStatistics segmentsLoadStatistics = computeSegmentLoadStatistics(segments);
      return Response.ok(
          ImmutableMap.of(
              dataSourceName,
              segmentsLoadStatistics.getNumUnavailableSegments()
          )
      ).build();
    } else if (full != null) {
      // Calculate response for full mode
      Map<String, Object2LongMap<String>> segmentLoadMap =
          coordinator.getTierToDatasourceToUnderReplicatedCount(segments, computeUsingClusterView != null);
      if (segmentLoadMap.isEmpty()) {
        return Response.serverError()
                       .entity("Coordinator segment replicant lookup is not initialized yet. Try again later.")
                       .build();
      }
      return Response.ok(segmentLoadMap).build();
    } else {
      // Calculate response for default mode
      SegmentsLoadStatistics segmentsLoadStatistics = computeSegmentLoadStatistics(segments);
      return Response.ok(
          ImmutableMap.of(
              dataSourceName,
              100 * ((double) (segmentsLoadStatistics.getNumLoadedSegments())
                     / (double) segmentsLoadStatistics.getNumPublishedSegments())
          )
      ).build();
    }
  }

  private SegmentsLoadStatistics computeSegmentLoadStatistics(Iterable<DataSegment> segments)
  {
    Map<SegmentId, SegmentLoadInfo> segmentLoadInfos = serverInventoryView.getLoadInfoForAllSegments();
    int numPublishedSegments = 0;
    int numUnavailableSegments = 0;
    int numLoadedSegments = 0;
    for (DataSegment segment : segments) {
      numPublishedSegments++;
      if (!segmentLoadInfos.containsKey(segment.getId())) {
        numUnavailableSegments++;
      } else {
        numLoadedSegments++;
      }
    }
    return new SegmentsLoadStatistics(numPublishedSegments, numUnavailableSegments, numLoadedSegments);
  }

  private static class SegmentsLoadStatistics
  {
    private final int numPublishedSegments;
    private final int numUnavailableSegments;
    private final int numLoadedSegments;

    SegmentsLoadStatistics(
        int numPublishedSegments,
        int numUnavailableSegments,
        int numLoadedSegments
    )
    {
      this.numPublishedSegments = numPublishedSegments;
      this.numUnavailableSegments = numUnavailableSegments;
      this.numLoadedSegments = numLoadedSegments;
    }

    public int getNumPublishedSegments()
    {
      return numPublishedSegments;
    }

    public int getNumUnavailableSegments()
    {
      return numUnavailableSegments;
    }

    public int getNumLoadedSegments()
    {
      return numLoadedSegments;
    }
  }

  /**
   * The property names belong to the public HTTP JSON API.
   */
  @PublicApi
  enum SimpleProperties
  {
    size,
    count
  }

  private Response getServedSegmentsInInterval(
      String dataSourceName,
      boolean full,
      Predicate<Interval> intervalFilter
  )
  {
    final ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);

    if (dataSource == null) {
      return logAndCreateDataSourceNotFoundResponse(dataSourceName);
    }

    final Comparator<Interval> comparator = Comparators.intervalsByStartThenEnd().reversed();

    if (full) {
      final Map<Interval, Map<SegmentId, Object>> retVal = new TreeMap<>(comparator);
      for (DataSegment dataSegment : dataSource.getSegments()) {
        if (intervalFilter.test(dataSegment.getInterval())) {
          Map<SegmentId, Object> segments = retVal.computeIfAbsent(dataSegment.getInterval(), i -> new HashMap<>());

          Pair<DataSegment, Set<String>> segmentAndServers = getServersWhereSegmentIsServed(dataSegment.getId());

          if (segmentAndServers != null) {
            segments.put(
                dataSegment.getId(),
                ImmutableMap.of("metadata", segmentAndServers.lhs, "servers", segmentAndServers.rhs)
            );
          }
        }
      }

      return Response.ok(retVal).build();
    } else {
      final Map<Interval, Map<SimpleProperties, Object>> statsPerInterval = new TreeMap<>(comparator);
      for (DataSegment dataSegment : dataSource.getSegments()) {
        if (intervalFilter.test(dataSegment.getInterval())) {
          Map<SimpleProperties, Object> properties =
              statsPerInterval.computeIfAbsent(dataSegment.getInterval(), i -> new EnumMap<>(SimpleProperties.class));
          properties.merge(SimpleProperties.size, dataSegment.getSize(), (a, b) -> (Long) a + (Long) b);
          properties.merge(SimpleProperties.count, 1, (a, b) -> (Integer) a + (Integer) b);
        }
      }

      return Response.ok(statsPerInterval).build();
    }
  }

  @GET
  @Path("/{dataSourceName}/segments")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getAllServedSegments(
      @PathParam("dataSourceName") String dataSourceName,
      @QueryParam("full") String full
  )
  {
    ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);
    if (dataSource == null) {
      return logAndCreateDataSourceNotFoundResponse(dataSourceName);
    }

    Response.ResponseBuilder builder = Response.ok();
    if (full != null) {
      return builder.entity(dataSource.getSegments()).build();
    }

    return builder.entity(Iterables.transform(dataSource.getSegments(), DataSegment::getId)).build();
  }

  @GET
  @Path("/{dataSourceName}/segments/{segmentId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getServedSegment(
      @PathParam("dataSourceName") String dataSourceName,
      @PathParam("segmentId") String segmentId
  )
  {
    ImmutableDruidDataSource dataSource = getQueryableDataSource(dataSourceName);
    if (dataSource == null) {
      return logAndCreateDataSourceNotFoundResponse(dataSourceName);
    }

    for (SegmentId possibleSegmentId : SegmentId.iteratePossibleParsingsWithDataSource(dataSourceName, segmentId)) {
      Pair<DataSegment, Set<String>> retVal = getServersWhereSegmentIsServed(possibleSegmentId);
      if (retVal != null) {
        return Response.ok(ImmutableMap.of("metadata", retVal.lhs, "servers", retVal.rhs)).build();
      }
    }
    log.warn("Segment id [%s] is unknown", segmentId);
    return Response.noContent().build();
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markSegmentAsUnused} instead.
   */
  @Deprecated
  @DELETE
  @Path("/{dataSourceName}/segments/{segmentId}")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response markSegmentAsUnused(
      @PathParam("dataSourceName") String dataSourceName,
      @PathParam("segmentId") String segmentIdString
  )
  {
    final SegmentId segmentId = SegmentId.tryParse(dataSourceName, segmentIdString);
    if (segmentId == null) {
      return Response.status(Response.Status.BAD_REQUEST).entity(
          org.apache.druid.java.util.common.StringUtils.format(
              "Could not parse Segment ID[%s] for DataSource[%s]",
              segmentIdString, dataSourceName
          )
      ).build();
    }

    RemoteSegmentUpdateOperation remoteOperation
        = () -> overlordClient.markSegmentAsUnused(segmentId);

    return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
  }

  /**
   * @deprecated Use {@code OverlordDataSourcesResource#markSegmentAsUsed} instead.
   */
  @Deprecated
  @POST
  @Path("/{dataSourceName}/segments/{segmentId}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response markSegmentAsUsed(
      @PathParam("dataSourceName") String dataSourceName,
      @PathParam("segmentId") String segmentIdString
  )
  {
    final SegmentId segmentId = SegmentId.tryParse(dataSourceName, segmentIdString);
    if (segmentId == null) {
      return Response.status(Response.Status.BAD_REQUEST).entity(
          org.apache.druid.java.util.common.StringUtils.format(
              "Could not parse Segment ID[%s] for DataSource[%s]",
              segmentIdString, dataSourceName
          )
      ).build();
    }

    RemoteSegmentUpdateOperation remoteOperation
        = () -> overlordClient.markSegmentAsUsed(segmentId);

    return updateSegmentsViaOverlord(dataSourceName, remoteOperation);
  }

  @GET
  @Path("/{dataSourceName}/tiers")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getTiersWhereSegmentsAreServed(@PathParam("dataSourceName") String dataSourceName)
  {
    Set<String> retVal = new HashSet<>();
    for (DruidServer druidServer : serverInventoryView.getInventory()) {
      if (druidServer.getDataSource(dataSourceName) != null) {
        retVal.add(druidServer.getTier());
      }
    }

    return Response.ok(retVal).build();
  }

  @Nullable
  private ImmutableDruidDataSource getQueryableDataSource(final String dataSourceName)
  {
    List<DruidDataSource> dataSources = serverInventoryView
        .getInventory()
        .stream()
        .map(server -> server.getDataSource(dataSourceName))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    if (dataSources.isEmpty()) {
      return null;
    }

    // Note: this logic doesn't guarantee that the result is a snapshot that ever existed in the cluster because all
    // DruidDataSource objects (belonging to different servers) are independently, concurrently mutable objects.
    // But this is OK because a "snapshot" hardly even makes sense in a distributed system anyway.
    final SortedMap<SegmentId, DataSegment> segmentMap = new TreeMap<>();
    for (DruidDataSource dataSource : dataSources) {
      Iterable<DataSegment> segments = dataSource.getSegments();
      for (DataSegment segment : segments) {
        segmentMap.put(segment.getId(), segment);
      }
    }

    return new ImmutableDruidDataSource(dataSourceName, Collections.emptyMap(), segmentMap);
  }

  @Nullable
  private Pair<DataSegment, Set<String>> getServersWhereSegmentIsServed(SegmentId segmentId)
  {
    DataSegment theSegment = null;
    Set<String> servers = new HashSet<>();
    for (DruidServer druidServer : serverInventoryView.getInventory()) {
      DataSegment currSegment = druidServer.getSegment(segmentId);
      if (currSegment != null) {
        theSegment = currSegment;
        servers.add(druidServer.getHost());
      }
    }

    if (theSegment == null) {
      return null;
    }

    return new Pair<>(theSegment, servers);
  }

  private Map<String, Object> makeSimpleDatasource(ImmutableDruidDataSource input)
  {
    return new ImmutableMap.Builder<String, Object>()
        .put("name", input.getName())
        .put("properties", getSimpleDatasource(input.getName()))
        .build();
  }

  private Map<String, Map<String, Object>> getSimpleDatasource(String dataSourceName)
  {
    Map<String, Object> tiers = new HashMap<>();
    Map<String, Object> segments = new HashMap<>();
    Map<String, Map<String, Object>> retVal = ImmutableMap.of(
        "tiers", tiers,
        "segments", segments
    );
    Set<SegmentId> totalDistinctSegments = new HashSet<>();
    Map<String, HashSet<Object>> tierDistinctSegments = new HashMap<>();

    long totalSegmentSize = 0;
    long totalReplicatedSize = 0;

    DateTime minTime = DateTimes.MAX;
    DateTime maxTime = DateTimes.MIN;
    String tier;
    for (DruidServer druidServer : serverInventoryView.getInventory()) {
      DruidDataSource druidDataSource = druidServer.getDataSource(dataSourceName);
      tier = druidServer.getTier();

      if (druidDataSource == null) {
        continue;
      }

      tierDistinctSegments.computeIfAbsent(tier, t -> new HashSet<>());

      long dataSourceSegmentSize = 0;
      long replicatedSegmentSize = 0;

      for (DataSegment dataSegment : druidDataSource.getSegments()) {
        // tier segments stats
        if (!tierDistinctSegments.get(tier).contains(dataSegment.getId())) {
          dataSourceSegmentSize += dataSegment.getSize();
          tierDistinctSegments.get(tier).add(dataSegment.getId());
        }
        // total segments stats
        if (totalDistinctSegments.add(dataSegment.getId())) {
          totalSegmentSize += dataSegment.getSize();

          minTime = DateTimes.min(minTime, dataSegment.getInterval().getStart());
          maxTime = DateTimes.max(maxTime, dataSegment.getInterval().getEnd());
        }
        totalReplicatedSize += dataSegment.getSize();
        replicatedSegmentSize += dataSegment.getSize();
      }

      // tier stats
      Map<String, Object> tierStats = (Map) tiers.get(tier);
      if (tierStats == null) {
        tierStats = new HashMap<>();
        tiers.put(druidServer.getTier(), tierStats);
      }
      tierStats.put("segmentCount", tierDistinctSegments.get(tier).size());

      long segmentSize = MapUtils.getLong(tierStats, "size", 0L);
      tierStats.put("size", segmentSize + dataSourceSegmentSize);

      long replicatedSize = MapUtils.getLong(tierStats, "replicatedSize", 0L);
      tierStats.put("replicatedSize", replicatedSize + replicatedSegmentSize);
    }

    segments.put("count", totalDistinctSegments.size());
    segments.put("size", totalSegmentSize);
    segments.put("replicatedSize", totalReplicatedSize);
    segments.put("minTime", minTime);
    segments.put("maxTime", maxTime);
    return retVal;
  }

  /**
   * Provides serverView for a datasource and Interval which gives details about servers hosting segments for an
   * interval. Used by the realtime tasks to fetch a view of the interval they are interested in.
   */
  @GET
  @Path("/{dataSourceName}/intervals/{interval}/serverview")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response getServedSegmentsInInterval(
      @PathParam("dataSourceName") String dataSourceName,
      @PathParam("interval") String interval,
      @QueryParam("partial") final boolean partial
  )
  {
    TimelineLookup<String, SegmentLoadInfo> timeline = serverInventoryView.getTimeline(
        new TableDataSource(dataSourceName)
    );
    final Interval theInterval = Intervals.of(interval.replace('_', '/'));
    if (timeline == null) {
      log.debug("No timeline found for datasource[%s]", dataSourceName);
      return Response.ok(new ArrayList<ImmutableSegmentLoadInfo>()).build();
    }

    return Response.ok(prepareServedSegmentsInInterval(timeline, theInterval)).build();
  }

  private Iterable<ImmutableSegmentLoadInfo> prepareServedSegmentsInInterval(
      TimelineLookup<String, SegmentLoadInfo> dataSourceServingTimeline,
      Interval interval
  )
  {
    Iterable<TimelineObjectHolder<String, SegmentLoadInfo>> lookup =
        dataSourceServingTimeline.lookupWithIncompletePartitions(interval);
    return FunctionalIterable
        .create(lookup)
        .transformCat(
            (TimelineObjectHolder<String, SegmentLoadInfo> input) ->
                Iterables.transform(
                    input.getObject(),
                    (PartitionChunk<SegmentLoadInfo> chunk) -> chunk.getObject().toImmutableSegmentLoadInfo()
                )
        );
  }

  /**
   * Used by the realtime tasks to learn whether a segment is handed off or not.
   * It returns true when the segment will never be handed off or is already handed off. Otherwise, it returns false.
   */
  @GET
  @Path("/{dataSourceName}/handoffComplete")
  @Produces(MediaType.APPLICATION_JSON)
  @ResourceFilters(DatasourceResourceFilter.class)
  public Response isHandOffComplete(
      @PathParam("dataSourceName") String dataSourceName,
      @QueryParam("interval") final String interval,
      @QueryParam("partitionNumber") final int partitionNumber,
      @QueryParam("version") final String version
  )
  {
    try {
      final List<Rule> rules = metadataRuleManager.getRulesWithDefault(dataSourceName);
      final Interval theInterval = Intervals.of(interval);
      final SegmentDescriptor descriptor = new SegmentDescriptor(theInterval, version, partitionNumber);
      final DateTime now = DateTimes.nowUtc();

      // A segment that is not eligible for load will never be handed off
      boolean eligibleForLoad = false;
      for (Rule rule : rules) {
        if (rule.appliesTo(theInterval, now)) {
          eligibleForLoad = rule instanceof LoadRule && ((LoadRule) rule).shouldMatchingSegmentBeLoaded();
          break;
        }
      }
      if (!eligibleForLoad) {
        return Response.ok(true).build();
      }

      VersionedIntervalTimeline<String, SegmentLoadInfo> timeline = serverInventoryView.getTimeline(
          new TableDataSource(dataSourceName)
      );
      if (timeline == null) {
        log.error("No timeline found for datasource[%s]", dataSourceName);
        return Response.ok(false).build();
      }

      // A segment with version lower than that of the latest chunk might never get handed off
      // If there are multiple versions of this segment (due to a concurrent replace task),
      // only the latest version would get handed off
      List<TimelineObjectHolder<String, SegmentLoadInfo>> timelineObjects = timeline.lookup(Intervals.of(interval));
      if (!timelineObjects.isEmpty() && timelineObjects.get(0).getVersion().compareTo(version) > 0) {
        return Response.ok(true).build();
      }

      Iterable<ImmutableSegmentLoadInfo> servedSegmentsInInterval =
          prepareServedSegmentsInInterval(timeline, theInterval);
      if (isSegmentLoaded(servedSegmentsInInterval, descriptor)) {
        return Response.ok(true).build();
      }

      return Response.ok(false).build();
    }
    catch (Exception e) {
      log.error(e, "Error while handling hand off check request");
      return Response.serverError().entity(ImmutableMap.of("error", e.toString())).build();
    }
  }

  static boolean isSegmentLoaded(Iterable<ImmutableSegmentLoadInfo> servedSegments, SegmentDescriptor descriptor)
  {
    for (ImmutableSegmentLoadInfo segmentLoadInfo : servedSegments) {
      if (segmentLoadInfo.getSegment().getInterval().contains(descriptor.getInterval())
          && segmentLoadInfo.getSegment().getShardSpec().getPartitionNum() == descriptor.getPartitionNumber()
          && segmentLoadInfo.getSegment().getVersion().compareTo(descriptor.getVersion()) >= 0
          && Iterables.any(
          segmentLoadInfo.getServers(), DruidServerMetadata::isSegmentReplicationTarget
      )) {
        return true;
      }
    }
    return false;
  }

}
