/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.authorization.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.keycloak.authorization.attribute.Attributes;
import org.keycloak.authorization.identity.Identity;
import org.keycloak.authorization.util.Tokens;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class KeycloakIdentity implements Identity {

    private final AccessToken accessToken;
    private final RealmModel realm;
    private final KeycloakSession keycloakSession;
    private final Attributes attributes;

    public KeycloakIdentity(KeycloakSession keycloakSession) {
        this(Tokens.getAccessToken(keycloakSession), keycloakSession);
    }

    public KeycloakIdentity(AccessToken accessToken, KeycloakSession keycloakSession) {
        this.accessToken = accessToken;

        if (this.accessToken == null) {
            throw new ErrorResponseException("invalid_bearer_token", "Could not obtain bearer access_token from request.", Status.FORBIDDEN);
        }

        this.keycloakSession = keycloakSession;
        this.realm = keycloakSession.getContext().getRealm();

        HashMap<String, Collection<String>> attributes = new HashMap<>();

        try {
            ObjectNode objectNode = JsonSerialization.createObjectNode(this.accessToken);
            Iterator<String> iterator = objectNode.fieldNames();
            List<String> roleNames = new ArrayList<>();

            while (iterator.hasNext()) {
                String fieldName = iterator.next();
                JsonNode fieldValue = objectNode.get(fieldName);
                List<String> values = new ArrayList<>();

                values.add(fieldValue.asText());

                if (fieldName.equals("realm_access")) {
                    JsonNode grantedRoles = fieldValue.get("roles");

                    if (grantedRoles != null) {
                        Iterator<JsonNode> rolesIt = grantedRoles.iterator();

                        while (rolesIt.hasNext()) {
                            roleNames.add(rolesIt.next().asText());
                        }
                    }
                }

                if (fieldName.equals("resource_access")) {
                    Iterator<JsonNode> resourceAccessIt = fieldValue.iterator();

                    while (resourceAccessIt.hasNext()) {
                        JsonNode grantedRoles = resourceAccessIt.next().get("roles");

                        if (grantedRoles != null) {
                            Iterator<JsonNode> rolesIt = grantedRoles.iterator();

                            while (rolesIt.hasNext()) {
                                roleNames.add(rolesIt.next().asText());
                            }
                        }
                    }
                }

                attributes.put(fieldName, values);
            }

            attributes.put("roles", roleNames);
        } catch (Exception e) {
            throw new RuntimeException("Error while reading attributes from security token.", e);
        }

        this.attributes = Attributes.from(attributes);
    }

    @Override
    public String getId() {
        if (isResourceServer()) {
            ClientSessionModel clientSession = this.keycloakSession.sessions().getClientSession(this.accessToken.getClientSession());
            return clientSession.getClient().getId();
        }

        return this.accessToken.getSubject();
    }

    @Override
    public Attributes getAttributes() {
        return this.attributes;
    }

    public AccessToken getAccessToken() {
        return this.accessToken;
    }

    private  boolean isResourceServer() {
        UserModel clientUser = null;

        if (this.accessToken.getClientSession() != null) {
            ClientSessionModel clientSession = this.keycloakSession.sessions().getClientSession(this.accessToken.getClientSession());
            clientUser = this.keycloakSession.users().getUserByServiceAccountClient(clientSession.getClient());
        } else if (this.accessToken.getIssuedFor() != null) {
            ClientModel clientModel = this.keycloakSession.realms().getClientById(this.accessToken.getIssuedFor(), this.realm);
            clientUser = this.keycloakSession.users().getUserByServiceAccountClient(clientModel);
        }


        if (clientUser == null) {
            return false;
        }

        return this.accessToken.getSubject().equals(clientUser.getId());
    }
}
