/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudgetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

public class PodDisruptionBudgetOperator extends AbstractResourceOperator<KubernetesClient, PodDisruptionBudget, PodDisruptionBudgetList, Resource<PodDisruptionBudget>> {

    public PodDisruptionBudgetOperator(Vertx vertx, KubernetesClient client) {
        super(vertx, client, "PodDisruptionBudget");

    }

    @Override
    protected MixedOperation<PodDisruptionBudget, PodDisruptionBudgetList, Resource<PodDisruptionBudget>> operation() {
        return client.policy().podDisruptionBudget();
    }

    @Override
    protected Future<ReconcileResult<PodDisruptionBudget>> internalPatch(String namespace, String name, PodDisruptionBudget current, PodDisruptionBudget desired, boolean cascading) {
        PodDisruptionBudgetDiff diff = new PodDisruptionBudgetDiff(current, desired);

        if (!diff.isEmpty())    {
            return super.internalPatch(namespace, name, current, desired, cascading);
        } else {
            return Future.succeededFuture(ReconcileResult.noop(desired));
        }
    }
}
