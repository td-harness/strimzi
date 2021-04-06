/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.api.conversion.converter;

import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.strimzi.api.annotations.ApiVersion;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnectS2IBuilder;
import org.junit.jupiter.api.Assertions;

class KafkaConnectS2IConverterTest extends SpecableConverterTestBase<KafkaConnectS2IConverter, KafkaConnectS2I> {
    @Override
    ExtConverters.ExtConverter<KafkaConnectS2I> specableConverter() {
        return ExtConverters.crConverter(new KafkaConnectS2IConverter());
    }

    @Override
    protected void convertTolerationsToV1beta2(String fromApiVersion) {
        KafkaConnectS2I converted = new KafkaConnectS2IBuilder()
            .withApiVersion(fromApiVersion)
            .withNewSpec()
            .withTolerations(new TolerationBuilder()
                .withKey("foo")
                .withEffect("dbdb")
                .build())
            .endSpec()
            .build();
        converted = specableConverter().testConvertTo(converted, ApiVersion.V1BETA2);
        Assertions.assertEquals("kafka.strimzi.io/v1beta2", converted.getApiVersion());
        Assertions.assertNull(converted.getSpec().getTolerations());
        Assertions.assertEquals(1, converted.getSpec().getTemplate().getPod().getTolerations().size());
        Assertions.assertEquals("foo", converted.getSpec().getTemplate().getPod().getTolerations().get(0).getKey());
        Assertions.assertEquals("dbdb", converted.getSpec().getTemplate().getPod().getTolerations().get(0).getEffect());
    }

    @Override
    protected void convertAffinityToV1beta2(String fromApiVersion) {
        KafkaConnectS2I converted = new KafkaConnectS2IBuilder()
            .withApiVersion(fromApiVersion)
            .withNewSpec()
            .withAffinity(new AffinityBuilder()
                .withNewNodeAffinity()
                .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                .withWeight(100)
                .endPreferredDuringSchedulingIgnoredDuringExecution()
                .endNodeAffinity()
                .build())
            .endSpec()
            .build();
        converted = specableConverter().testConvertTo(converted, ApiVersion.V1BETA2);
        Assertions.assertEquals("kafka.strimzi.io/v1beta2", converted.getApiVersion());
        Assertions.assertNull(converted.getSpec().getAffinity());
        Assertions.assertNotNull(converted.getSpec().getTemplate().getPod().getAffinity());
        Assertions.assertEquals(100, converted.getSpec().getTemplate().getPod().getAffinity().getNodeAffinity().getPreferredDuringSchedulingIgnoredDuringExecution().get(0).getWeight());
    }
}