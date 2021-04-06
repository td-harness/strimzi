/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.micrometer.core.instrument.Counter;
import io.strimzi.operator.common.model.NamespaceAndName;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstraction of an operator which is driven by resources of a given {@link #kind()}.
 *
 * {@link #reconcile(Reconciliation)} triggers the asynchronous reconciliation of a named resource.
 * Reconciliation of a given resource may be triggered either by a Kubernetes watch event (see {@link OperatorWatcher}) or
 * on a regular schedule.
 * {@link #reconcileAll(String, String, Handler)} triggers reconciliation of all the resources that the operator consumes.
 * An operator instance is not bound to a particular namespace. Rather the namespace is passed as a parameter.
 */
public interface Operator {

    /**
     * The Kubernetes kind of the resource "consumed" by this operator
     * @return The kind.
     */
    String kind();

    /**
     * Reconcile the resource identified by the given reconciliation.
     * @param reconciliation The resource.
     * @return A Future is completed once the resource has been reconciled.
     */
    Future<Void> reconcile(Reconciliation reconciliation);

    /**
     * Triggers the asynchronous reconciliation of all resources which this operator consumes.
     * The resources to reconcile are identified by {@link #allResourceNames(String)}.
     * @param trigger The cause of this reconciliation (for logging).
     * @param namespace The namespace to reconcile, or {@code *} to reconcile across all namespaces.
     * @param handler Handler called on completion.
     */
    default void reconcileAll(String trigger, String namespace, Handler<AsyncResult<Void>> handler) {
        allResourceNames(namespace).onComplete(ar -> {
            getPausedResourceCounter().set(0);
            if (ar.succeeded()) {
                reconcileThese(trigger, ar.result(), handler);
                getPeriodicReconciliationsCounter().increment();
            } else {
                handler.handle(ar.map((Void) null));
            }
        });
    }

    default void reconcileThese(String trigger, Set<NamespaceAndName> desiredNames, Handler<AsyncResult<Void>> handler) {
        if (desiredNames.size() > 0) {
            List<Future> futures = new ArrayList<>();
            getResourceCounter().set(desiredNames.size());

            for (NamespaceAndName resourceRef : desiredNames) {
                Reconciliation reconciliation = new Reconciliation(trigger, kind(), resourceRef.getNamespace(), resourceRef.getName());
                futures.add(reconcile(reconciliation));
            }
            CompositeFuture.join(futures).map((Void) null).onComplete(handler);
        } else {
            getResourceCounter().set(0);
            handler.handle(Future.succeededFuture());
        }
    }

    /**
     * Returns a future which completes with the names of all the resources to be reconciled by
     * {@link #reconcileAll(String, String, Handler)}.
     *
     * @param namespace The namespace
     * @return The set of resource names
     */
    Future<Set<NamespaceAndName>> allResourceNames(String namespace);

    /**
     * A selector for narrowing the resources which this operator instance consumes to those whose labels match this selector.
     * @return A selector.
     */
    default Optional<LabelSelector> selector() {
        return Optional.empty();
    }

    Counter getPeriodicReconciliationsCounter();

    AtomicInteger getResourceCounter();

    AtomicInteger getPausedResourceCounter();
}
