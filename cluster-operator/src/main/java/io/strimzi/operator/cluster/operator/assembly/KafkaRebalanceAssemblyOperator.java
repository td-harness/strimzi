/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.KafkaRebalanceList;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.model.CruiseControlResources;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaRebalance;
import io.strimzi.api.kafka.model.KafkaRebalanceBuilder;
import io.strimzi.api.kafka.model.KafkaRebalanceSpec;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.KafkaRebalanceStatus;
import io.strimzi.api.kafka.model.status.KafkaRebalanceStatusBuilder;
import io.strimzi.api.kafka.model.balancing.KafkaRebalanceAnnotation;
import io.strimzi.api.kafka.model.balancing.KafkaRebalanceState;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.CruiseControl;
import io.strimzi.operator.cluster.model.InvalidResourceException;
import io.strimzi.operator.cluster.model.NoSuchResourceException;
import io.strimzi.operator.cluster.model.StatusDiff;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApi;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApiImpl;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlRestException;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlUserTaskStatus;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.RebalanceOptions;
import io.strimzi.operator.cluster.operator.resource.ResourceOperatorSupplier;
import io.strimzi.operator.common.AbstractOperator;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.operator.resource.AbstractWatchableStatusedResourceOperator;
import io.strimzi.operator.common.operator.resource.CrdOperator;
import io.strimzi.operator.common.operator.resource.StatusUtils;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlApi.CC_REST_API_SUMMARY;
import static io.strimzi.operator.common.Annotations.ANNO_STRIMZI_IO_REBALANCE;

/**
 * <p>Assembly operator for a "KafkaRebalance" assembly, which interacts with the Cruise Control REST API</p>
 *
 * <p>
 *     This operator takes care of the {@code KafkaRebalance} custom resource type that a user can create in order
 *     to interact with the Cruise Control REST API and execute a cluster rebalance.
 *     A state machine is used for the rebalancing flow which is reflected in the {@code status} of the custom resource.
 *
 *     When a new {@code KafkaRebalance} custom resource is created, the operator sends a rebalance proposal
 *     request to the Cruise Control REST API and moves to the {@code PendingProposal} state. It stays in this state
 *     until a the rebalance proposal is ready, polling the related status on Cruise Control, and then finally moves
 *     to the {@code ProposalReady} state. The status of the {@code KafkaRebalance} custom resource is updated with the
 *     computed rebalance proposal so that the user can view it and making a decision to execute it or not.
 *
 *     For starting the actual rebalance on the cluster, the user annotates the custom resource with
 *     the {@code strimzi.io/rebalance=approve} annotation, triggering the operator to send a rebalance request to the
 *     Cruise Control REST API in order to execute the rebalancing.
 *     During the rebalancing, the operator state machine is in the {@code Rebalancing} state and it moves finally
 *     to the {@code Ready} state when the rebalancing is done.
 *
 *     The user is able to stop the retrieval of an in-progress rebalance proposal computation (if it is taking a long
 *     time and they no longer want it) by annotating the custom resource with {@code strimzi.io/rebalance=stop} when
 *     it is in the {@code PendingProposal} state. This will prevent the operator waiting for Cruise Control to complete
 *     the proposal. There is no way to stop the preparation of a optimization proposal on the Cruise Control side. The
 *     operator then moves to the {@code Stopped} state and the user can request a new proposal by applying the
 *     {@code strimzi.io/rebalance=refresh} annotation on the custom resource.
 *
 *     The user can stop an ongoing rebalance by annotating the custom resource with {@code strimzi.io/rebalance=stop}
 *     when it is in the {@code Rebalancing} state. The operator then moves to the {@code Stopped} state. The ongoing
 *     partition reassignement will complete and further reassignements will be cancelled. The user can request a new
 *     proposal by applying the {@code strimzi.io/rebalance=refresh} annotation on the custom resource.
 *
 *     Finally, when a proposal is ready but the user has not approved it right after the computation, the proposal may
 *     be in a stale state as the cluster conditions might be changed. The proposal can be refreshed by annotating
 *     the custom resource with the {@code strimzi.io/rebalance=refresh} annotation.
 * </p>
 * <pre><code>
 *   User        Kube           Operator              CC
 *    | Create KR  |               |                   |
 *    |-----------→|   Watch       |                   |
 *    |            |--------------→|   Proposal        |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            | Update Status |------------------→|
 *    |            |←--------------|                   |
 *    |            |   Watch       |                   |
 *    |            |--------------→|                   |
 *    | Get        |               |                   |
 *    |-----------→|               |                   |
 *    |            |               |                   |
 *    | Approve    |               |                   |
 *    |-----------→|  Watch        |                   |
 *    |            |--------------→|   Rebalance       |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            |               |------------------→|
 *    |            |               |   Poll            |
 *    |            | Update Status |------------------→|
 *    |            |←--------------|                   |
 *    |            |   Watch       |                   |
 *    |            |--------------→|                   |
 *    | Get        |               |                   |
 *    |-----------→|               |                   |
 * </code></pre>
 */
public class KafkaRebalanceAssemblyOperator
        extends AbstractOperator<KafkaRebalance, KafkaRebalanceSpec, KafkaRebalanceStatus, AbstractWatchableStatusedResourceOperator<KubernetesClient, KafkaRebalance, KafkaRebalanceList, Resource<KafkaRebalance>>> {

    private static final Logger log = LogManager.getLogger(KafkaRebalanceAssemblyOperator.class.getName());

    private static final long REBALANCE_POLLING_TIMER_MS = 5_000;
    private static final int MAX_API_RETRIES = 5;

    private final CrdOperator<KubernetesClient, KafkaRebalance, KafkaRebalanceList> kafkaRebalanceOperator;
    private final CrdOperator<KubernetesClient, Kafka, KafkaList> kafkaOperator;
    private final PlatformFeaturesAvailability pfa;
    private final Optional<LabelSelector> kafkaSelector;

    /**
     * @param vertx The Vertx instance
     * @param pfa Platform features availability properties
     * @param supplier Supplies the operators for different resources
     * @param config Cluster Operator configuration
     */
    public KafkaRebalanceAssemblyOperator(Vertx vertx, PlatformFeaturesAvailability pfa,
                                          ResourceOperatorSupplier supplier, ClusterOperatorConfig config) {
        super(vertx, KafkaRebalance.RESOURCE_KIND, supplier.kafkaRebalanceOperator, supplier.metricsProvider, null);
        this.kafkaSelector = (config.getCustomResourceSelector() == null || config.getCustomResourceSelector().toMap().isEmpty()) ? Optional.empty() : Optional.of(new LabelSelector(null, config.getCustomResourceSelector().toMap()));
        this.pfa = pfa;
        this.kafkaRebalanceOperator = supplier.kafkaRebalanceOperator;
        this.kafkaOperator = supplier.kafkaOperator;
    }

    /**
     * Provides an implementation of the Cruise Control API client
     *
     * @return Cruise Control API client instance
     */
    protected CruiseControlApi cruiseControlClientProvider() {
        return new CruiseControlApiImpl(vertx);
    }

    /**
     * The Cruise Control hostname to connect to
     *
     * @param clusterName the Kafka cluster resource name
     * @param clusterNamespace the namespace of the Kafka cluster
     * @return the Cruise Control hostname to connect to
     */
    protected String cruiseControlHost(String clusterName, String clusterNamespace) {
        return CruiseControlResources.qualifiedServiceName(clusterName, clusterNamespace);
    }

    /**
     * Create a watch on {@code KafkaRebalance} in the given {@code watchNamespaceOrWildcard}.
     *
     * @param watchNamespaceOrWildcard The namespace to watch, or "*" to watch all namespaces.
     * @return A future which completes when the watch has been set up.
     */
    public Future<Void> createRebalanceWatch(String watchNamespaceOrWildcard) {

        return Util.async(this.vertx, () -> {
            kafkaRebalanceOperator.watch(watchNamespaceOrWildcard, selector(), new Watcher<KafkaRebalance>() {
                @Override
                public void eventReceived(Action action, KafkaRebalance kafkaRebalance) {
                    Reconciliation reconciliation = new Reconciliation("kafkarebalance-watch", kafkaRebalance.getKind(),
                            kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName());

                    log.debug("{}: EventReceived {} on {} with status [{}] and {}={}", reconciliation, action,
                            kafkaRebalance.getMetadata().getName(),
                            kafkaRebalance.getStatus() != null ? rebalanceStateConditionType(kafkaRebalance.getStatus()) : null,
                            ANNO_STRIMZI_IO_REBALANCE, rawRebalanceAnnotation(kafkaRebalance));

                    withLock(reconciliation, LOCK_TIMEOUT_MS,
                        () -> reconcileRebalance(reconciliation, action == Action.DELETED ? null : kafkaRebalance));
                }

                @Override
                public void onClose(WatcherException e) {
                    if (e != null) {
                        throw new KubernetesClientException(e.getMessage());
                    }
                }

            });
            return null;
        });
    }

    /**
     * Searches through the conditions in the supplied status instance and finds those whose type matches one of the values defined
     * in the {@link KafkaRebalanceState} enum.
     * If there are none it will return null.
     * If there is only one it will return that Condition.
     * If there are more than one it will throw a RuntimeException.
     *
     * @param status The KafkaRebalanceStatus instance whose conditions will be searched.
     * @return The Condition instance from the supplied status that has a type value matching one of the values of the
     *         {@link KafkaRebalanceState} enum. If none are found then the method will return null.
     * @throws RuntimeException If there is more than one Condition instance in the supplied status whose type matches one of the
     *                          {@link KafkaRebalanceState} enum values.
     */
    /* test */ protected Condition rebalanceStateCondition(KafkaRebalanceStatus status) {
        if (status.getConditions() != null) {

            List<Condition> statusConditions = status.getConditions()
                    .stream()
                    .filter(condition -> condition.getType() != null)
                    .filter(condition -> Arrays.stream(KafkaRebalanceState.values())
                            .anyMatch(stateValue -> stateValue.toString().equals(condition.getType())))
                    .collect(Collectors.toList());

            if (statusConditions.size() == 1) {
                return statusConditions.get(0);
            } else if (statusConditions.size() > 1) {
                throw new RuntimeException("Multiple KafkaRebalance State Conditions were present in the KafkaRebalance status");
            }
        }
        // If there are no conditions or none that have the correct status
        return null;
    }

    /**
     * Searches through the conditions in the supplied status instance and finds those whose type matches one of the values defined
     * in the {@link KafkaRebalanceState} enum.
     * If there are none it will return null.
     * If there are more than one it will throw a RuntimeException.
     * If there is only one it will return that Condition's type as a string.
     *
     * @param status The status instance whose conditions will be searched.
     * @return The type of the rebalance status condition.
     * @throws RuntimeException If there is more than one Condition instance in the supplied status whose type matches one of the
     *                          {@link KafkaRebalanceState} enum values.
     */
    private String rebalanceStateConditionType(KafkaRebalanceStatus status) {
        Condition rebalanceStateCondition = rebalanceStateCondition(status);
        return rebalanceStateCondition != null ? rebalanceStateCondition.getType() : null;
    }

    private Future<KafkaRebalance> updateStatus(KafkaRebalance kafkaRebalance,
                                                KafkaRebalanceStatus desiredStatus,
                                                Throwable e) {
        // Leave the current status when the desired state is null
        if (desiredStatus != null) {

            Condition cond = rebalanceStateCondition(desiredStatus);

            List<Condition> previous = Collections.emptyList();
            if (desiredStatus.getConditions() != null) {
                previous = desiredStatus.getConditions().stream().filter(condition -> condition != cond).collect(Collectors.toList());
            }
            String rebalanceType = rebalanceStateConditionType(desiredStatus);

            // If a throwable is supplied, it is set in the status with priority
            if (e != null) {
                StatusUtils.setStatusConditionAndObservedGeneration(kafkaRebalance, desiredStatus, KafkaRebalanceState.NotReady.toString(), e);
                desiredStatus.setConditions(Stream.concat(desiredStatus.getConditions().stream(), previous.stream()).collect(Collectors.toList()));
            } else if (rebalanceType != null) {
                StatusUtils.setStatusConditionAndObservedGeneration(kafkaRebalance, desiredStatus, rebalanceType);
                desiredStatus.setConditions(Stream.concat(desiredStatus.getConditions().stream(), previous.stream()).collect(Collectors.toList()));
            } else {
                throw new IllegalArgumentException("Status related exception and the Status condition's type cannot both be null");
            }

            StatusDiff diff = new StatusDiff(kafkaRebalance.getStatus(), desiredStatus);
            if (!diff.isEmpty()) {
                return kafkaRebalanceOperator
                        .updateStatusAsync(new KafkaRebalanceBuilder(kafkaRebalance).withStatus(desiredStatus).build());
            }
        }
        return Future.succeededFuture(kafkaRebalance);
    }

    private RebalanceOptions.RebalanceOptionsBuilder convertRebalanceSpecToRebalanceOptions(KafkaRebalanceSpec kafkaRebalanceSpec) {

        RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder = new RebalanceOptions.RebalanceOptionsBuilder();

        if (kafkaRebalanceSpec.getGoals() != null) {
            rebalanceOptionsBuilder.withGoals(kafkaRebalanceSpec.getGoals());
        }
        if (kafkaRebalanceSpec.isSkipHardGoalCheck()) {
            rebalanceOptionsBuilder.withSkipHardGoalCheck();
        }
        if (kafkaRebalanceSpec.getExcludedTopics() != null) {
            rebalanceOptionsBuilder.withExcludedTopics(kafkaRebalanceSpec.getExcludedTopics());
        }
        if (kafkaRebalanceSpec.getConcurrentPartitionMovementsPerBroker() > 0) {
            rebalanceOptionsBuilder.withConcurrentPartitionMovementsPerBroker(kafkaRebalanceSpec.getConcurrentPartitionMovementsPerBroker());
        }
        if (kafkaRebalanceSpec.getConcurrentIntraBrokerPartitionMovements() > 0) {
            rebalanceOptionsBuilder.withConcurrentIntraPartitionMovements(kafkaRebalanceSpec.getConcurrentIntraBrokerPartitionMovements());
        }
        if (kafkaRebalanceSpec.getConcurrentLeaderMovements() > 0) {
            rebalanceOptionsBuilder.withConcurrentLeaderMovements(kafkaRebalanceSpec.getConcurrentLeaderMovements());
        }
        if (kafkaRebalanceSpec.getReplicationThrottle() > 0) {
            rebalanceOptionsBuilder.withReplicationThrottle(kafkaRebalanceSpec.getReplicationThrottle());
        }
        if (kafkaRebalanceSpec.getReplicaMovementStrategies() != null) {
            rebalanceOptionsBuilder.withReplicaMovementStrategies(kafkaRebalanceSpec.getReplicaMovementStrategies());
        }

        return rebalanceOptionsBuilder;

    }

    private Future<Void> reconcile(Reconciliation reconciliation, String host,
                                   CruiseControlApi apiClient, KafkaRebalance kafkaRebalance,
                                   KafkaRebalanceState currentState, KafkaRebalanceAnnotation rebalanceAnnotation) {

        log.info("{}: Rebalance action from state [{}]", reconciliation, currentState);

        if (Annotations.isReconciliationPausedWithAnnotation(kafkaRebalance)) {
            // we need to do this check again because it was triggered by a watcher
            KafkaRebalanceStatus status = new KafkaRebalanceStatus();

            Set<Condition> unknownAndDeprecatedConditions = validate(kafkaRebalance);
            unknownAndDeprecatedConditions.add(StatusUtils.getPausedCondition());
            status.setConditions(new ArrayList<>(unknownAndDeprecatedConditions));

            return updateStatus(kafkaRebalance, status, null).compose(i -> Future.succeededFuture());
        }

        RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder = convertRebalanceSpecToRebalanceOptions(kafkaRebalance.getSpec());

        return computeNextStatus(reconciliation, host, apiClient, kafkaRebalance, currentState, rebalanceAnnotation, rebalanceOptionsBuilder)
           .compose(desiredStatus -> {
               // More events related to resource modification might be queued with a stale state. (potentially updated by the rebalance holding the lock)
               // Due to possible long rebalancing operations that take the lock for the entire period,
               // do a new get to retrieve the current resource state.
               return kafkaRebalanceOperator.getAsync(reconciliation.namespace(), reconciliation.name())
                            .compose(currentKafkaRebalance -> {
                                if (currentKafkaRebalance != null) {
                                    return updateStatus(currentKafkaRebalance, desiredStatus, null)
                                            .compose(updatedKafkaRebalance -> {
                                                log.info("{}: State updated to [{}] with annotation {}={} ",
                                                        reconciliation,
                                                        rebalanceStateConditionType(updatedKafkaRebalance.getStatus()),
                                                        ANNO_STRIMZI_IO_REBALANCE,
                                                        rawRebalanceAnnotation(updatedKafkaRebalance));
                                                if (hasRebalanceAnnotation(updatedKafkaRebalance)) {
                                                    log.debug("{}: Removing annotation {}={}", reconciliation,
                                                            ANNO_STRIMZI_IO_REBALANCE,
                                                            rawRebalanceAnnotation(updatedKafkaRebalance));
                                                    // Updated KafkaRebalance has rebalance annotation removed as
                                                    // action specified by user has been completed.
                                                    KafkaRebalance patchedKafkaRebalance = new KafkaRebalanceBuilder(updatedKafkaRebalance)
                                                            .editMetadata()
                                                                .removeFromAnnotations(ANNO_STRIMZI_IO_REBALANCE)
                                                            .endMetadata()
                                                            .build();

                                                    return kafkaRebalanceOperator.patchAsync(patchedKafkaRebalance);
                                                } else {
                                                    log.debug("{}: No annotation {}", reconciliation, ANNO_STRIMZI_IO_REBALANCE);
                                                    return Future.succeededFuture();
                                                }
                                            })
                                            .mapEmpty();
                                } else {
                                    return Future.succeededFuture();
                                }
                            }, exception -> {
                                    log.error("{}: Status updated to [NotReady] due to error: {}", reconciliation, exception.getMessage());
                                    return updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), exception)
                                            .mapEmpty();
                                }); },
               exception -> {
                   log.error("{}: Status updated to [NotReady] due to error: {}", reconciliation, exception.getMessage());
                   return updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), exception)
                       .mapEmpty();
               });
    }

    /**
     * computeNextStatus returns a future to a KafkaRebalanceStatus that will be computed depending on the given
     * KafkaRebalance state, status and annotations.
     */
    /* test */ protected Future<KafkaRebalanceStatus> computeNextStatus(Reconciliation reconciliation,
                                                                        String host, CruiseControlApi apiClient,
                                                                        KafkaRebalance kafkaRebalance, KafkaRebalanceState currentState,
                                                                        KafkaRebalanceAnnotation rebalanceAnnotation, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        switch (currentState) {
            case New:
                return onNew(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
            case PendingProposal:
                return onPendingProposal(reconciliation, host, apiClient, kafkaRebalance, rebalanceAnnotation, rebalanceOptionsBuilder);
            case ProposalReady:
                return onProposalReady(reconciliation, host, apiClient, kafkaRebalance, rebalanceAnnotation, rebalanceOptionsBuilder);
            case Rebalancing:
                return onRebalancing(reconciliation, host, apiClient, kafkaRebalance, rebalanceAnnotation);
            case Stopped:
                return onStop(reconciliation, host, apiClient, kafkaRebalance, rebalanceAnnotation, rebalanceOptionsBuilder);
            case Ready:
                // Rebalance Complete
                return Future.succeededFuture(buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), validate(kafkaRebalance)));
            case NotReady:
                // Error case
                return onNotReady(reconciliation, host, apiClient, kafkaRebalance, rebalanceAnnotation, rebalanceOptionsBuilder);
            default:
                return Future.failedFuture(new RuntimeException("Unexpected state " + currentState));
        }
    }

    private KafkaRebalanceStatus buildRebalanceStatus(String sessionID, KafkaRebalanceState cruiseControlState, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(StatusUtils.buildRebalanceCondition(cruiseControlState.toString()));
        conditions.addAll(validation);
        return new KafkaRebalanceStatusBuilder()
                .withSessionId(sessionID)
                .withConditions(conditions)
                .build();
    }

    private KafkaRebalanceStatus buildRebalanceStatusFromPreviousStatus(KafkaRebalanceStatus currentStatus, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.addAll(validation);

        Condition currentState = rebalanceStateCondition(currentStatus);
        conditions.add(currentState);

        return new KafkaRebalanceStatusBuilder()
                .withSessionId(currentStatus.getSessionId())
                .withOptimizationResult(currentStatus.getOptimizationResult())
                .withConditions(conditions)
                .build();
    }

    private KafkaRebalanceStatus buildRebalanceStatus(String sessionID, KafkaRebalanceState cruiseControlState, Map<String, Object> optimizationResult, Set<Condition> validation) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(StatusUtils.buildRebalanceCondition(cruiseControlState.toString()));
        conditions.addAll(validation);
        return new KafkaRebalanceStatusBuilder()
                .withSessionId(sessionID)
                .withOptimizationResult(optimizationResult)
                .withConditions(conditions)
                .build();
    }

    /**
     * This method handles the transition from the {@code New} state.
     * When a new {@code KafkaRebalance} is created, it calls the Cruise Control API requesting a rebalance proposal.
     *
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still processing it, the next state is {@code PendingProposal}.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaRebalanceStatus} including the state
     */
    private Future<KafkaRebalanceStatus> onNew(Reconciliation reconciliation,
                                               String host, CruiseControlApi apiClient,
                                               KafkaRebalance kafkaRebalance, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        return requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder, kafkaRebalance);
    }

    /**
     * This method handles the transition from the {@code NotReady} state.
     * This state indicates that the rebalance has suffered some kind of error.
     * This could be due to misconfiguration or the result of an error during a reconcile.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaRebalanceStatus} including the state
     */
    private Future<KafkaRebalanceStatus> onNotReady(Reconciliation reconciliation,
                                                    String host, CruiseControlApi apiClient,
                                                    KafkaRebalance kafkaRebalance,
                                                    KafkaRebalanceAnnotation rebalanceAnnotation,
                                                    RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.refresh) {
            // The user has fixed the error on the resource and want to 'refresh'
            // This actually requests a new rebalance proposal
            return onNew(reconciliation, host, apiClient, kafkaRebalance, rebalanceOptionsBuilder);
        } else {
            // Stay in the current NotReady state, returning null as next state
            return Future.succeededFuture();
        }
    }

    /**
     * This method handles the transition from {@code PendingProposal} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance proposal processing on Cruise Control side.
     * In order to do that, it calls the Cruise Control API for requesting the rebalance proposal.
     * When the proposal is ready, the next state is {@code ProposalReady}.
     * If the user sets the strimzi.io/rebalance=stop annotation, it stops polling the Cruise Control API for requesting the rebalance proposal.
     * If the user sets any other values for the strimzi.io/rebalance annotation, it is ignored and the rebalance proposal request continues.
     *
     * This method holds the lock until the rebalance proposal is ready or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaRebalanceStatus} including the state
     */
    private Future<KafkaRebalanceStatus> onPendingProposal(Reconciliation reconciliation,
                                                           String host, CruiseControlApi apiClient,
                                                           KafkaRebalance kafkaRebalance,
                                                           KafkaRebalanceAnnotation rebalanceAnnotation,
                                                           RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        Promise<KafkaRebalanceStatus> p = Promise.promise();
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.none) {
            log.debug("{}: Starting Cruise Control rebalance proposal request timer", reconciliation);
            vertx.setPeriodic(REBALANCE_POLLING_TIMER_MS, t ->
                kafkaRebalanceOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                    .onSuccess(currentKafkaRebalance -> {
                        // Checking that the resource was not deleted between periodic polls
                        if (currentKafkaRebalance != null) {
                            // Check resource is in the right state as previous execution might have set the status and completed the future
                            // Safety check as timer might be called again (from a delayed timer firing)
                            if (state(currentKafkaRebalance) == KafkaRebalanceState.PendingProposal) {
                                if (rebalanceAnnotation(currentKafkaRebalance) == KafkaRebalanceAnnotation.stop) {
                                    log.debug("{}: Stopping current Cruise Control proposal request timer", reconciliation);
                                    vertx.cancelTimer(t);
                                    p.complete(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, validate(currentKafkaRebalance)));
                                } else {
                                    requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder,
                                            currentKafkaRebalance.getStatus().getSessionId(), currentKafkaRebalance)
                                        .onSuccess(rebalanceStatus -> {
                                            // If the returned status has an optimization result then the rebalance proposal
                                            // is ready, so stop the polling
                                            if (rebalanceStatus.getOptimizationResult() != null &&
                                                    !rebalanceStatus.getOptimizationResult().isEmpty()) {
                                                vertx.cancelTimer(t);
                                                log.debug("{}: Optimization proposal ready", reconciliation);
                                                p.complete(rebalanceStatus);
                                            } else {
                                                // The rebalance proposal is still not ready yet, keep the timer for polling
                                                log.debug("{}: Waiting for optimization proposal to be ready", reconciliation);
                                            }
                                        })
                                        .onFailure(e -> {
                                            log.error("{}: Cruise Control getting rebalance proposal failed", reconciliation, e.getCause());
                                            vertx.cancelTimer(t);
                                            p.fail(e.getCause());
                                        });
                                }
                            } else {
                                p.complete(currentKafkaRebalance.getStatus());
                            }
                        } else {
                            log.debug("{}: Rebalance resource was deleted, stopping the request time", reconciliation);
                            vertx.cancelTimer(t);
                            p.complete();
                        }
                    })
                    .onFailure(e -> {
                        log.error("{}: Cruise Control getting rebalance resource failed", reconciliation, e.getCause());
                        vertx.cancelTimer(t);
                        p.fail(e.getCause());
                    })
            );
        } else {
            p.complete(kafkaRebalance.getStatus());
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code ProposalReady} state.
     * It is related to the value that the user apply to the strimzi.io/rebalance annotation.
     * If the strimzi.io/rebalance annotation is set to 'approve', it calls the Cruise Control API for executing the proposed rebalance.
     * If the strimzi.io/rebalance annotation is set to 'refresh', it calls the Cruise Control API for for requesting/refreshing the ready rebalance proposal.
     * If the rebalance is immediately complete, the next state is {@code Ready}.
     * If the rebalance is not finished yet as Cruise Control is still processing it (the usual case), the next state is {@code Rebalancing}.
     * If the user sets any other value for the strimzi.io/rebalance annotation, it is ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance request
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaRebalanceStatus} including the state
     */
    private Future<KafkaRebalanceStatus> onProposalReady(Reconciliation reconciliation,
                                                         String host, CruiseControlApi apiClient,
                                                         KafkaRebalance kafkaRebalance,
                                                         KafkaRebalanceAnnotation rebalanceAnnotation,
                                                         RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        switch (rebalanceAnnotation) {
            case none:
                log.debug("{}: No {} annotation set", reconciliation, ANNO_STRIMZI_IO_REBALANCE);
                return Future.succeededFuture(buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), validate(kafkaRebalance)));
            case approve:
                log.debug("{}: Annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, KafkaRebalanceAnnotation.approve);
                return requestRebalance(reconciliation, host, apiClient, false, rebalanceOptionsBuilder, kafkaRebalance);
            case refresh:
                log.debug("{}: Annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, KafkaRebalanceAnnotation.refresh);
                return requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder, kafkaRebalance);
            default:
                log.warn("{}: Ignore annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotation);
                return Future.succeededFuture(buildRebalanceStatusFromPreviousStatus(kafkaRebalance.getStatus(), validate(kafkaRebalance)));
        }
    }

    /**
     * This method handles the transition from {@code Rebalancing} state.
     * It starts a periodic timer in order to check the status of the ongoing rebalance processing on Cruise Control side.
     * In order to do that, it calls the related Cruise Control REST API about asking the user task status.
     * When the rebalance is finished, the next state is {@code Ready}.
     * If the user sets the strimzi.io/rebalance annotation to 'stop', it calls the Cruise Control REST API for stopping the ongoing task
     * and then transitions to the {@code Stopped} state.
     * If the user sets any other values for the strimzi.io/rebalance annotation, it is just ignored and the user task checks continue.
     * This method holds the lock until the rebalance is finished, the ongoing task is stopped or any exception is raised.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the REST API requests
     * @param apiClient Cruise Control REST API client instance
     * @param kafkaRebalance Current {@code KafkaRebalance} resource
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @return a Future with the next {@code KafkaRebalanceStatus} including the state
     */
    private Future<KafkaRebalanceStatus> onRebalancing(Reconciliation reconciliation,
                                                       String host, CruiseControlApi apiClient,
                                                       KafkaRebalance kafkaRebalance,
                                                       KafkaRebalanceAnnotation rebalanceAnnotation) {
        Promise<KafkaRebalanceStatus> p = Promise.promise();
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.none) {
            log.info("{}: Starting Cruise Control rebalance user task status timer", reconciliation);
            String sessionId = kafkaRebalance.getStatus().getSessionId();
            AtomicInteger ccApiErrorCount = new AtomicInteger();
            vertx.setPeriodic(REBALANCE_POLLING_TIMER_MS, t -> {
                // Check that we have not already failed to contact the API beyond the allowed number of times.
                if (ccApiErrorCount.get() >= MAX_API_RETRIES) {
                    vertx.cancelTimer(t);
                    p.fail(new CruiseControlRestException("Unable to reach Cruise Control API after " + MAX_API_RETRIES + " attempts"));
                }
                kafkaRebalanceOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                    .onSuccess(currentKafkaRebalance -> {
                        // Checking that the resource was not deleted between periodic polls
                        if (currentKafkaRebalance != null) {
                            // Check resource is in the right state as previous execution might have set the status and completed the future
                            // Safety check as timer might be called again (from a delayed timer firing)
                            if (state(currentKafkaRebalance) == KafkaRebalanceState.Rebalancing) {
                                if (rebalanceAnnotation(currentKafkaRebalance) == KafkaRebalanceAnnotation.stop) {
                                    log.debug("{}: Stopping current Cruise Control rebalance user task", reconciliation);
                                    vertx.cancelTimer(t);
                                    apiClient.stopExecution(host, CruiseControl.REST_API_PORT)
                                        .onSuccess(r -> p.complete(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, validate(kafkaRebalance))))
                                        .onFailure(e -> {
                                            log.error("{}: Cruise Control stopping execution failed", reconciliation, e.getCause());
                                            p.fail(e.getCause());
                                        });
                                } else {
                                    log.info("{}: Getting Cruise Control rebalance user task status", reconciliation);
                                    apiClient.getUserTaskStatus(host, CruiseControl.REST_API_PORT, sessionId)
                                        .onSuccess(cruiseControlResponse -> {
                                            JsonObject taskStatusJson = cruiseControlResponse.getJson();
                                            CruiseControlUserTaskStatus taskStatus = CruiseControlUserTaskStatus.lookup(taskStatusJson.getString("Status"));
                                            switch (taskStatus) {
                                                case COMPLETED:
                                                    vertx.cancelTimer(t);
                                                    log.info("{}: Rebalance ({}) is now complete", reconciliation, sessionId);
                                                    p.complete(buildRebalanceStatus(
                                                        null, KafkaRebalanceState.Ready, taskStatusJson.getJsonObject(CC_REST_API_SUMMARY).getMap(), validate(kafkaRebalance)));
                                                    break;
                                                case COMPLETED_WITH_ERROR:
                                                    // TODO: There doesn't seem to be a way to retrieve the actual error message from the user tasks endpoint?
                                                    //       We may need to propose an upstream PR for this.
                                                    // TODO: Once we can get the error details we need to add an error field to the Rebalance Status to hold
                                                    //       details of any issues while rebalancing.
                                                    log.error("{}: Rebalance ({}) optimization proposal has failed to complete", reconciliation, sessionId);
                                                    vertx.cancelTimer(t);
                                                    p.complete(buildRebalanceStatus(sessionId, KafkaRebalanceState.NotReady, validate(kafkaRebalance)));
                                                    break;
                                                case IN_EXECUTION: // Rebalance is still in progress
                                                    // We need to check that the status has been updated with the ongoing optimisation proposal
                                                    // The proposal field can be empty if a rebalance(dryrun=false) was called and the optimisation
                                                    // proposal was still being prepared (in progress). In that case the rebalance will start when
                                                    // the proposal is complete but the optimisation proposal summary will be missing.
                                                    if (currentKafkaRebalance.getStatus().getOptimizationResult() == null ||
                                                            currentKafkaRebalance.getStatus().getOptimizationResult().isEmpty()) {
                                                        log.info("{}: Rebalance ({}) optimization proposal is now ready and has been added to the status", reconciliation, sessionId);
                                                        // Cancel the timer so that the status is returned and updated.
                                                        vertx.cancelTimer(t);
                                                        p.complete(buildRebalanceStatus(
                                                            sessionId, KafkaRebalanceState.Rebalancing, taskStatusJson.getJsonObject(CC_REST_API_SUMMARY).getMap(), validate(kafkaRebalance)));
                                                    }
                                                    ccApiErrorCount.set(0);
                                                    // TODO: Find out if there is any way to check the progress of a rebalance.
                                                    //       We could parse the verbose proposal for total number of reassignments and compare to number completed (if available)?
                                                    //       We can then update the status at this point.
                                                    break;
                                                case ACTIVE: // Rebalance proposal is still being calculated
                                                    // If a rebalance(dryrun=false) was called and the proposal is still being prepared then the task
                                                    // will be in an ACTIVE state. When the proposal is ready it will shift to IN_EXECUTION and we will
                                                    // check that the optimisation proposal is added to the status on the next reconcile.
                                                    log.info("{}: Rebalance ({}) optimization proposal is still being prepared", reconciliation, sessionId);
                                                    ccApiErrorCount.set(0);
                                                    break;
                                                default:
                                                    log.error("{}: Unexpected state {}", reconciliation, taskStatus);
                                                    vertx.cancelTimer(t);
                                                    p.fail("Unexpected state " + taskStatus);
                                                    break;
                                            }
                                        })
                                        .onFailure(e -> {
                                            log.error("{}: Cruise Control getting rebalance task status failed", reconciliation, e.getCause());
                                            // To make sure this error is not just a temporary problem with the network we retry several times.
                                            // If the number of errors pass the MAX_API_ERRORS limit then the period method will fail the promise.
                                            ccApiErrorCount.getAndIncrement();
                                        });
                                }
                            } else {
                                p.complete(currentKafkaRebalance.getStatus());
                            }
                        } else {
                            log.debug("{}: Rebalance resource was deleted, stopping the request time", reconciliation);
                            vertx.cancelTimer(t);
                            p.complete();
                        }
                    })
                    .onFailure(e -> {
                        log.error("{}: Cruise Control getting rebalance resource failed", reconciliation, e.getCause());
                        vertx.cancelTimer(t);
                        p.fail(e.getCause());
                    });
            });
        } else {
            p.complete(kafkaRebalance.getStatus());
        }
        return p.future();
    }

    /**
     * This method handles the transition from {@code Stopped} state.
     * If the user set strimzi.io/rebalance=refresh annotation, it calls the Cruise Control API for requesting a new rebalance proposal.
     * If the proposal is immediately ready, the next state is {@code ProposalReady}.
     * If the proposal is not ready yet and Cruise Control is still taking care of processing it, the next state is {@code PendingProposal}.
     * If the user sets any other values for the strimzi.io/rebalance, it is just ignored.
     *
     * @param reconciliation Reconciliation information
     * @param host Cruise Control service to which sending the rebalance proposal request
     * @param apiClient Cruise Control REST API client instance
     * @param rebalanceAnnotation The current value for the strimzi.io/rebalance annotation
     * @param rebalanceOptionsBuilder builder for the Cruise Control REST API client options
     * @return a Future with the next {@code KafkaRebalanceStatus} bringing the state
     */
    private Future<KafkaRebalanceStatus> onStop(Reconciliation reconciliation,
                                                String host, CruiseControlApi apiClient,
                                                KafkaRebalance kafkaRebalance, KafkaRebalanceAnnotation rebalanceAnnotation,
                                                RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder) {
        if (rebalanceAnnotation == KafkaRebalanceAnnotation.refresh) {
            return requestRebalance(reconciliation, host, apiClient, true, rebalanceOptionsBuilder, kafkaRebalance);
        } else {
            log.warn("{}: Ignore annotation {}={}", reconciliation, ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotation);
            return Future.succeededFuture(buildRebalanceStatus(null, KafkaRebalanceState.Stopped, validate(kafkaRebalance)));
        }
    }

    /**
     * Reconcile loop for the KafkaRebalance
     */
    /* test */ Future<Void> reconcileRebalance(Reconciliation reconciliation, KafkaRebalance kafkaRebalance) {
        if (kafkaRebalance == null) {
            log.info("{}: Rebalance resource deleted", reconciliation);
            return Future.succeededFuture();
        }

        String clusterName = kafkaRebalance.getMetadata().getLabels() == null ? null : kafkaRebalance.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
        String clusterNamespace = kafkaRebalance.getMetadata().getNamespace();
        if (clusterName == null) {
            log.warn("{}: Resource lacks label '{}': No cluster related to a possible rebalance.", reconciliation, Labels.STRIMZI_CLUSTER_LABEL);
            return updateStatus(kafkaRebalance, new KafkaRebalanceStatus(),
                    new InvalidResourceException("Resource lacks label '"
                            + Labels.STRIMZI_CLUSTER_LABEL
                            + "': No cluster related to a possible rebalance.")).mapEmpty();
        }

        // Get associated Kafka cluster state
        return kafkaOperator.getAsync(clusterNamespace, clusterName)
                .compose(kafka -> {
                    if (kafka == null) {
                        log.warn("{}: Kafka resource '{}' identified by label '{}' does not exist in namespace {}.",
                                reconciliation, clusterName, Labels.STRIMZI_CLUSTER_LABEL, clusterNamespace);
                        return updateStatus(kafkaRebalance, new KafkaRebalanceStatus(),
                                new NoSuchResourceException("Kafka resource '" + clusterName
                                        + "' identified by label '" + Labels.STRIMZI_CLUSTER_LABEL
                                        + "' does not exist in namespace " + clusterNamespace + ".")).mapEmpty();
                    } else if (!Util.matchesSelector(kafkaSelector, kafka)) {
                        log.debug("{}: {} {} in namespace {} belongs to a Kafka cluster {} which does not match label selector {} and will be ignored", reconciliation, kind(), kafkaRebalance.getMetadata().getName(), clusterNamespace, clusterName, kafkaSelector.get().getMatchLabels());
                        return Future.succeededFuture();
                    } else if (kafka.getSpec().getCruiseControl() == null) {
                        log.warn("{}: Kafka resource lacks 'cruiseControl' declaration : No deployed Cruise Control for doing a rebalance.", reconciliation);
                        return updateStatus(kafkaRebalance, new KafkaRebalanceStatus(),
                                new InvalidResourceException("Kafka resource lacks 'cruiseControl' declaration "
                                        + ": No deployed Cruise Control for doing a rebalance.")).mapEmpty();
                    }

                    CruiseControlApi apiClient = cruiseControlClientProvider();

                    // get latest KafkaRebalance state as it may have changed
                    return kafkaRebalanceOperator.getAsync(kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName())
                        .compose(currentKafkaRebalance -> {
                            KafkaRebalanceStatus kafkaRebalanceStatus = currentKafkaRebalance.getStatus();
                            KafkaRebalanceState currentState;
                            // cluster rebalance is new or it is in one of the others states
                            if (kafkaRebalanceStatus == null || kafkaRebalanceStatus.getConditions().stream().filter(cond -> "ReconciliationPaused".equals(cond.getType())).findAny().isPresent()) {
                                currentState = KafkaRebalanceState.New;
                            } else {
                                String rebalanceStateType = rebalanceStateConditionType(kafkaRebalanceStatus);
                                if (rebalanceStateType == null) {
                                    throw new RuntimeException("Unable to find KafkaRebalance State in current KafkaRebalance status");
                                }
                                currentState = KafkaRebalanceState.valueOf(rebalanceStateType);
                            }
                            // Check annotation
                            KafkaRebalanceAnnotation rebalanceAnnotation = rebalanceAnnotation(currentKafkaRebalance);
                            return reconcile(reconciliation, cruiseControlHost(clusterName, clusterNamespace),
                                        apiClient, currentKafkaRebalance, currentState, rebalanceAnnotation).mapEmpty();

                        }, exception -> Future.failedFuture(exception).mapEmpty());
                }, exception -> updateStatus(kafkaRebalance, new KafkaRebalanceStatus(), exception).mapEmpty());
    }

    private Future<KafkaRebalanceStatus> requestRebalance(Reconciliation reconciliation,
                                                          String host, CruiseControlApi apiClient,
                                                          boolean dryrun, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder,
                                                          KafkaRebalance kafkaRebalance) {
        return requestRebalance(reconciliation, host, apiClient, dryrun, rebalanceOptionsBuilder, null, kafkaRebalance);
    }

    private Future<KafkaRebalanceStatus> requestRebalance(Reconciliation reconciliation, String host, CruiseControlApi apiClient,
                                                          boolean dryrun, RebalanceOptions.RebalanceOptionsBuilder rebalanceOptionsBuilder, String userTaskID, KafkaRebalance kafkaRebalance) {

        log.info("{}: Requesting Cruise Control rebalance [dryrun={}]", reconciliation, dryrun);
        if (!dryrun) {
            rebalanceOptionsBuilder.withFullRun();
        }
        return apiClient.rebalance(host, CruiseControl.REST_API_PORT, rebalanceOptionsBuilder.build(), userTaskID)
                .map(response -> {
                    if (dryrun) {
                        if (response.isNotEnoughDataForProposal()) {
                            // If there is not enough data for a rebalance, it's an error at the Cruise Control level
                            // Need to re-request the proposal at a later time so move to the PendingProposal State.
                            return buildRebalanceStatus(null, KafkaRebalanceState.PendingProposal, validate(kafkaRebalance));
                        } else if (response.isProposalStillCalaculating()) {
                            // If rebalance proposal is still being processed, we need to re-request the proposal at a later time
                            // with the corresponding session-id so we move to the PendingProposal State.
                            return buildRebalanceStatus(response.getUserTaskId(), KafkaRebalanceState.PendingProposal, validate(kafkaRebalance));
                        }
                    } else {
                        if (response.isNotEnoughDataForProposal()) {
                            // We do not include a session id with this status as we do not want to retrieve the state of
                            // this failed tasks (COMPLETED_WITH_ERROR)
                            return buildRebalanceStatus(null, KafkaRebalanceState.PendingProposal, validate(kafkaRebalance));
                        } else if (response.isProposalStillCalaculating()) {
                            // If dryrun=false and the proposal is not ready we are going to be in a rebalancing state as
                            // soon as it is ready, so set the state to rebalancing.
                            // In the onRebalancing method the optimization proposal will be added when it is ready.
                            return buildRebalanceStatus(response.getUserTaskId(), KafkaRebalanceState.Rebalancing, validate(kafkaRebalance));
                        }
                    }

                    // If there is sufficient data and the proposal is complete (the response has the "summary" key)
                    if (!response.getJson().containsKey(CC_REST_API_SUMMARY)) {
                        throw new CruiseControlRestException("Rebalance returned unknown response: " + response.toString());
                    }

                    // Transition to ProposalReady for a dry run or to the Rebalancing state for a full run
                    KafkaRebalanceState newState = dryrun ? KafkaRebalanceState.ProposalReady : KafkaRebalanceState.Rebalancing;
                    return buildRebalanceStatus(response.getUserTaskId(), newState, response.getJson().getJsonObject(CC_REST_API_SUMMARY).getMap(), validate(kafkaRebalance));
                });
    }

    /**
     * Return the {@code RebalanceAnnotation} enum value for the raw String value of the strimzi.io/rebalance annotation
     * set on the provided KafkaRebalance resource instance.
     * If the annotation is not set it returns {@code RebalanceAnnotation.none} while if it's a not valid value, it
     * returns {@code RebalanceAnnotation.unknown}.
     *
     * @param kafkaRebalance KafkaRebalance resource instance from which getting the value of the strimzio.io/rebalance annotation
     * @return the {@code RebalanceAnnotation} enum value for the raw String value of the strimzio.io/rebalance annotation
     */
    private KafkaRebalanceAnnotation rebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        String rebalanceAnnotationValue = rawRebalanceAnnotation(kafkaRebalance);
        KafkaRebalanceAnnotation rebalanceAnnotation;
        try {
            rebalanceAnnotation = rebalanceAnnotationValue == null ?
                    KafkaRebalanceAnnotation.none : KafkaRebalanceAnnotation.valueOf(rebalanceAnnotationValue);
        } catch (IllegalArgumentException e) {
            rebalanceAnnotation = KafkaRebalanceAnnotation.unknown;
            log.warn("Wrong annotation value {}={} on {}/{}",
                    ANNO_STRIMZI_IO_REBALANCE, rebalanceAnnotationValue,
                    kafkaRebalance.getMetadata().getNamespace(), kafkaRebalance.getMetadata().getName());
        }
        return rebalanceAnnotation;
    }

    /**
     * Return the raw String value of the strimzio.io/rebalance annotation, if exists, on the provided
     * KafkaRebalance resource instance otherwise return null
     *
     * @param kafkaRebalance KafkaRebalance resource instance from which getting the value of the strimzio.io/rebalance annotation
     * @return the value for the strimzio.io/rebalance annotation on the provided KafkaRebalance resource instance
     */
    private String rawRebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        return hasRebalanceAnnotation(kafkaRebalance) ?
                kafkaRebalance.getMetadata().getAnnotations().get(ANNO_STRIMZI_IO_REBALANCE) : null;

    }

    /**
     * Return true if the provided KafkaRebalance resource instance has the strimzio.io/rebalance annotation
     *
     * @param kafkaRebalance KafkaRebalance resource instance to check
     * @return if the provided KafkaRebalance resource instance has the strimzio.io/rebalance annotation
     */
    private boolean hasRebalanceAnnotation(KafkaRebalance kafkaRebalance) {
        return kafkaRebalance.getMetadata().getAnnotations() != null &&
                kafkaRebalance.getMetadata().getAnnotations().containsKey(ANNO_STRIMZI_IO_REBALANCE);
    }

    private KafkaRebalanceState state(KafkaRebalance kafkaRebalance) {
        KafkaRebalanceStatus rebalanceStatus = kafkaRebalance.getStatus();
        if (rebalanceStatus != null) {
            String statusString = rebalanceStateConditionType(rebalanceStatus);
            if (statusString != null) {
                return KafkaRebalanceState.valueOf(statusString);
            }
        }
        return null;
    }

    @Override
    protected Future<KafkaRebalanceStatus> createOrUpdate(Reconciliation reconciliation, KafkaRebalance resource) {
        return reconcileRebalance(reconciliation, resource).map(v -> (KafkaRebalanceStatus) null);
    }

    @Override
    protected Future<Boolean> delete(Reconciliation reconciliation) {
        return reconcileRebalance(reconciliation, null).map(v -> Boolean.TRUE);
    }

    @Override
    protected KafkaRebalanceStatus createStatus() {
        return new KafkaRebalanceStatus();
    }
}
