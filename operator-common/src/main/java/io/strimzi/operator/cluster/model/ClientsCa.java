/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.api.kafka.model.CertificateExpirationPolicy;
import io.strimzi.certs.CertManager;
import io.strimzi.operator.common.PasswordGenerator;

public class ClientsCa extends Ca {
    public ClientsCa(CertManager certManager, PasswordGenerator passwordGenerator, String caCertSecretName, Secret clientsCaCert,
                     String caSecretKeyName, Secret clientsCaKey,
                     int validityDays, int renewalDays, boolean generateCa, CertificateExpirationPolicy policy) {
        super(certManager, passwordGenerator, "clients-ca",
                caCertSecretName, forceRenewal(clientsCaCert, clientsCaKey, "clients-ca.key"),
                caSecretKeyName, adapt060ClientsCaSecret(clientsCaKey),
                validityDays, renewalDays, generateCa, policy);
    }

    /**
     * In Strimzi 0.6.0 the Secrets and keys used a different convention.
     * Here we adapt the keys in the {@code *-clients-ca} Secret to match what
     * 0.7.0 expects.
     * @param clientsCaKey The secret to adapt.
     * @return The same Secret instance.
     */
    public static Secret adapt060ClientsCaSecret(Secret clientsCaKey) {
        if (clientsCaKey != null && clientsCaKey.getData() != null) {
            String key = clientsCaKey.getData().get("clients-ca.key");
            if (key != null) {
                clientsCaKey.getData().put("ca.key", key);
            }
        }
        return clientsCaKey;
    }

    @Override
    public String toString() {
        return "clients-ca";
    }
}
