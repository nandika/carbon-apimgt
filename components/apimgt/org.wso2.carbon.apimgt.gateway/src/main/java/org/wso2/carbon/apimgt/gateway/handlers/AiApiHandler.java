/*
 * Copyright (c) 2024 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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

package org.wso2.carbon.apimgt.gateway.handlers;

import com.google.gson.Gson;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.utils.URIBuilder;
import org.apache.synapse.MessageContext;
import org.apache.synapse.api.ApiUtils;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.AbstractHandler;
import org.apache.synapse.rest.RESTConstants;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.json.XML;
import org.wso2.carbon.apimgt.api.model.AIConfiguration;
import org.wso2.carbon.apimgt.api.model.AIEndpointConfiguration;
import org.wso2.carbon.apimgt.gateway.handlers.security.AuthenticationContext;
import org.wso2.carbon.apimgt.keymgt.model.entity.API;
import org.wso2.carbon.apimgt.api.APIConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants;
import org.wso2.carbon.apimgt.api.LLMProviderService;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.gateway.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.api.LLMProviderConfiguration;
import org.wso2.carbon.apimgt.api.LLMProviderMetadata;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.core.util.CryptoUtil;

import javax.ws.rs.core.MediaType;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * AiAPIHandler handles AI-specific API requests and responses.
 * It extends the AbstractSynapseHandler to integrate with the Synapse MessageContext.
 */
public class AiApiHandler extends AbstractHandler {

    private static final Log log = LogFactory.getLog(AiApiHandler.class);

    /**
     * Handles the incoming request flow.
     *
     * @param messageContext the Synapse MessageContext
     * @return true if the handling is successful, otherwise false
     */
    @Override
    public boolean handleRequest(MessageContext messageContext) {

        try {
            return processMessage(messageContext, true);
        } catch (APIManagementException | XMLStreamException | IOException e) {
            log.error("Error occurred while processing AI API", e);
        }
        return true;
    }

    /**
     * Handles the incoming response flow.
     *
     * @param messageContext the Synapse MessageContext
     * @return true if the handling is successful, otherwise false
     */
    @Override
    public boolean handleResponse(MessageContext messageContext) {

        try {
            return processMessage(messageContext, false);
        } catch (APIManagementException | XMLStreamException | IOException e) {
            log.error("Error occurred while processing AI API", e);
        }
        return true;
    }

    /**
     * Processes the message to extract and set LLM metadata.
     *
     * @param messageContext the Synapse MessageContext
     * @return true if the processing is successful, otherwise false
     */
    private boolean processMessage(MessageContext messageContext, boolean isRequest)
            throws APIManagementException, XMLStreamException, IOException {

        String path = ApiUtils.getFullRequestPath(messageContext);
        String tenantDomain = GatewayUtils.getTenantDomain();

        TreeMap<String, API> selectedAPIS = Utils.getSelectedAPIList(path, tenantDomain);
        String selectedPath = selectedAPIS.firstKey();
        API selectedAPI = selectedAPIS.get(selectedPath);

        if (selectedAPI == null) {
            log.error("Unable to find API for path: " + path + " in tenant domain: " + tenantDomain);
            return true;
        }

        AIConfiguration aiConfiguration = selectedAPI.getAiConfiguration();

        if (aiConfiguration == null) {
            log.debug("Unable to find AI configuration for API: " + selectedAPI.getApiId()
                    + " in tenant domain: " + tenantDomain);
            return true;
        }

        String llmProviderId = aiConfiguration.getLlmProviderId();
        String config = DataHolder.getInstance().getLLMProviderConfigurations(llmProviderId);
        if (config == null) {
            log.error("Unable to find provider configurations for provider: " + llmProviderId);
            return true;
        }

        LLMProviderConfiguration providerConfiguration = new Gson().fromJson(config, LLMProviderConfiguration.class);


        try {
            addEndpointConfigurationToMessageContext(messageContext, aiConfiguration.getAiEndpointConfiguration(), providerConfiguration);
        } catch (CryptoException | URISyntaxException e) {
            log.error("Error occurred while adding endpoint security configuration to message context", e);
            return true;
        }

        LLMProviderService llmProviderService =
                ServiceReferenceHolder.getInstance().getLLMProviderService(providerConfiguration.getConnectorType());

        if (llmProviderService == null) {
            log.error("Unable to find LLM provider service for provider: "
                    + llmProviderId);
            return true;
        }

        String payload = extractPayloadFromContext(messageContext, providerConfiguration);
        Map<String, String> queryParams = extractQueryParamsFromContext(messageContext);
        Map<String, String> headers = extractHeadersFromContext(messageContext);

        Map<String, String> metadataMap = isRequest
                ? llmProviderService.getRequestMetadata(payload, headers, queryParams, providerConfiguration.getMetadata())
                : llmProviderService.getResponseMetadata(payload, headers, queryParams, providerConfiguration.getMetadata());
        if (metadataMap != null && !metadataMap.isEmpty()) {
            String metadataProperty = isRequest
                    ? APIConstants.AIAPIConstants.AI_API_REQUEST_METADATA
                    : APIConstants.AIAPIConstants.AI_API_RESPONSE_METADATA;
            ((Axis2MessageContext) messageContext).getAxis2MessageContext().setProperty(metadataProperty, metadataMap);
        }

        return true;
    }

    private void addEndpointConfigurationToMessageContext(MessageContext messageContext,
                                                          AIEndpointConfiguration aiConfiguration,
                                                          LLMProviderConfiguration providerConfiguration)
            throws CryptoException, URISyntaxException {
        if (aiConfiguration != null) {
            org.apache.axis2.context.MessageContext axCtx =
                    ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            if (providerConfiguration.getAuthHeader() != null) {
                if (((AuthenticationContext) messageContext.getProperty("__API_AUTH_CONTEXT")).getKeyType().equals(org.wso2.carbon.apimgt.impl.APIConstants.API_KEY_TYPE_PRODUCTION)) {
                    Map transportHeaders =
                            (Map) axCtx.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
                    transportHeaders.put(providerConfiguration.getAuthHeader(),
                            decryptSecret(aiConfiguration.getProductionAuthValue()));
                } else {
                    Map transportHeaders =
                            (Map) axCtx.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
                    transportHeaders.put(providerConfiguration.getAuthHeader(),
                            decryptSecret(aiConfiguration.getSandboxAuthValue()));
                }
            }
            if (providerConfiguration.getAuthQueryParameter() != null) {
                if (((AuthenticationContext) messageContext.getProperty("__API_AUTH_CONTEXT")).getKeyType().equals("PRODUCTION")) {
                    URI updatedFullPath =
                            (new URIBuilder((String) axCtx.getProperty("REST_URL_POSTFIX"))).addParameter(providerConfiguration.getAuthQueryParameter(), decryptSecret(aiConfiguration.getProductionAuthValue())).build();
                    axCtx.setProperty("REST_URL_POSTFIX", updatedFullPath.toString());
                } else {
                    URI updatedFullPath =
                            (new URIBuilder((String) axCtx.getProperty("REST_URL_POSTFIX"))).addParameter(providerConfiguration.getAuthQueryParameter(), decryptSecret(aiConfiguration.getSandboxAuthValue())).build();
                    axCtx.setProperty("REST_URL_POSTFIX", updatedFullPath.toString());
                }
            }
        }
    }

    private String decryptSecret(String cipherText) throws CryptoException {

        CryptoUtil cryptoUtil = CryptoUtil.getDefaultCryptoUtil();
        return new String(cryptoUtil.base64DecodeAndDecrypt(cipherText));
    }

    /**
     * Extracts the payload from the given MessageContext based on LLM Provider configuration.
     *
     * @param messageContext The message context containing the payload.
     * @param config         The LLM Provider configuration to check for input source.
     * @return The extracted payload as a string, or null if not found.
     * @throws XMLStreamException If an error occurs while processing XML.
     * @throws IOException        If an I/O error occurs.
     */
    private String extractPayloadFromContext(MessageContext messageContext, LLMProviderConfiguration config)
            throws XMLStreamException, IOException {

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        for (LLMProviderMetadata metadata : config.getMetadata()) {
            if (APIConstants.AIAPIConstants.INPUT_SOURCE_PAYLOAD.equals(metadata.getInputSource())) {
                return getPayload(axis2MessageContext);
            }
        }
        return null;
    }

    /**
     * Extracts query parameters from the request path in the given MessageContext.
     *
     * @param messageContext The message context containing the request path.
     * @return A map of query parameters.
     */
    private Map<String, String> extractQueryParamsFromContext(MessageContext messageContext) {

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        Object requestPathObj = axis2MessageContext.getProperties().get(RESTConstants.REST_SUB_REQUEST_PATH);
        if (requestPathObj == null || requestPathObj.toString().isEmpty()) {
            log.warn("No request path available in the message context.");
            return new HashMap<>();
        }

        String requestPath = requestPathObj.toString();
        return extractQueryParams(requestPath);
    }

    /**
     * Extracts transport headers from the given MessageContext.
     *
     * @param messageContext The message context containing transport headers.
     * @return A map of transport headers.
     */
    private Map<String, String> extractHeadersFromContext(MessageContext messageContext) {

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
        return (Map<String, String>) axis2MessageContext
                .getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
    }

    /**
     * Extracts query parameters from the given request path.
     *
     * @param requestPath The request path containing query parameters.
     * @return A map of query parameter names and values.
     */
    public Map<String, String> extractQueryParams(String requestPath) {

        Map<String, String> queryParams = new HashMap<>();
        if (requestPath.contains("?")) {
            String queryString = requestPath.split("\\?")[1];
            String[] pairs = queryString.split("&");

            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    queryParams.put(keyValue[0], keyValue[1]);
                } else if (keyValue.length == 1) {
                    queryParams.put(keyValue[0], "");
                }
            }
        }

        return queryParams;
    }

    /**
     * Extracts the payload from the Axis2 MessageContext based on content type.
     *
     * @param axis2MessageContext the Axis2 MessageContext
     * @return the extracted payload as a String
     * @throws IOException        if an I/O error occurs
     * @throws XMLStreamException if an XML parsing error occurs
     */
    private String getPayload(org.apache.axis2.context.MessageContext axis2MessageContext)
            throws IOException, XMLStreamException {

        RelayUtils.buildMessage(axis2MessageContext);
        String contentType = (String) axis2MessageContext.getProperty(APIMgtGatewayConstants.REST_CONTENT_TYPE);
        if (contentType != null) {
            String normalizedContentType = contentType.toLowerCase();

            if (normalizedContentType.contains(MediaType.APPLICATION_XML) ||
                    normalizedContentType.contains(MediaType.TEXT_XML)) {
                return axis2MessageContext.getEnvelope().getBody().getFirstElement().toString();
            } else if (normalizedContentType.contains(MediaType.APPLICATION_JSON)) {
                String jsonString = axis2MessageContext.getEnvelope().getBody().getFirstElement().toString();
                jsonString = jsonString
                        .substring(jsonString.indexOf(">") + 1, jsonString.lastIndexOf("</jsonObject>"));
                return XML.toJSONObject(jsonString).toString();
            } else if (normalizedContentType.contains(MediaType.TEXT_PLAIN)) {
                return axis2MessageContext.getEnvelope().getBody().getFirstElement().getText();
            }
        }
        return null;
    }
}