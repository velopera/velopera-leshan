/*******************************************************************************
 * Copyright (c) 2023 Sierra Wireless and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.integration.tests.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.leshan.core.util.TestToolBox.uriHandler;
import static org.eclipse.leshan.integration.tests.util.assertion.Assertions.assertArg;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.leshan.bsserver.LeshanBootstrapServer;
import org.eclipse.leshan.client.LeshanClient;
import org.eclipse.leshan.client.bootstrap.BootstrapConsistencyChecker;
import org.eclipse.leshan.client.endpoint.LwM2mClientEndpoint;
import org.eclipse.leshan.client.endpoint.LwM2mClientEndpointsProvider;
import org.eclipse.leshan.client.engine.ClientEndpointNameProvider;
import org.eclipse.leshan.client.engine.RegistrationEngineFactory;
import org.eclipse.leshan.client.notification.NotificationDataStore;
import org.eclipse.leshan.client.observer.LwM2mClientObserver;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.send.DataSender;
import org.eclipse.leshan.client.servers.LwM2mServer;
import org.eclipse.leshan.client.util.LinkFormatHelper;
import org.eclipse.leshan.core.ResponseCode;
import org.eclipse.leshan.core.endpoint.EndPointUriHandler;
import org.eclipse.leshan.core.endpoint.EndpointUri;
import org.eclipse.leshan.core.link.LinkSerializer;
import org.eclipse.leshan.core.link.lwm2m.attributes.LwM2mAttributeParser;
import org.eclipse.leshan.core.node.codec.LwM2mDecoder;
import org.eclipse.leshan.core.node.codec.LwM2mEncoder;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpoint;
import org.eclipse.leshan.transport.californium.client.endpoint.CaliforniumClientEndpoint;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

public class LeshanTestClient extends LeshanClient {

    private final LwM2mClientObserver clientObserver;

    private final String endpointName;
    private final InOrder inOrder;
    private final ReverseProxy proxy;
    private NotificationDataStore notificationDataStore;

    public LeshanTestClient(ClientEndpointNameProvider endpointNameProvider,
            List<? extends LwM2mObjectEnabler> objectEnablers, List<DataSender> dataSenders,
            List<Certificate> trustStore, RegistrationEngineFactory engineFactory, BootstrapConsistencyChecker checker,
            Map<String, String> additionalAttributes, Map<String, String> bsAdditionalAttributes, LwM2mEncoder encoder,
            LwM2mDecoder decoder, ScheduledExecutorService sharedExecutor, LinkSerializer linkSerializer,
            LinkFormatHelper linkFormatHelper, LwM2mAttributeParser attributeParser, EndPointUriHandler uriHandler,
            LwM2mClientEndpointsProvider endpointsProvider, ReverseProxy proxy) {
        super(endpointNameProvider, objectEnablers, dataSenders, trustStore, engineFactory, checker,
                additionalAttributes, bsAdditionalAttributes, encoder, decoder, sharedExecutor, linkSerializer,
                linkFormatHelper, attributeParser, uriHandler, endpointsProvider);

        // Store some internal attribute
        this.endpointName = endpointNameProvider.getEndpointName();

        this.proxy = proxy;

        // Add Mock Listener
        clientObserver = mock(LwM2mClientObserver.class);
        addObserver(clientObserver);
        inOrder = inOrder(clientObserver);
    }

    @Override
    protected NotificationDataStore createNotificationStore() {
        notificationDataStore = super.createNotificationStore();
        return notificationDataStore;
    }

    public NotificationDataStore getNotificationDataStore() {
        return notificationDataStore;
    }

    public String getEndpointName() {
        return endpointName;
    }

    public Connector getClientConnector(LwM2mServer server) {
        CaliforniumClientEndpoint endpoint = (CaliforniumClientEndpoint) getEndpoint(server);
        return endpoint.getCoapEndpoint().getConnector();
    }

    public void clearSecurityContextFor(LwM2mServer server) {
        // TODO there is something not so good with this abstraction ...
        LwM2mClientEndpoint endpoint = this.getEndpoint(server);
        if (endpoint instanceof CaliforniumClientEndpoint) {
            Connector connector = ((CaliforniumClientEndpoint) endpoint).getCoapEndpoint().getConnector();
            if (connector instanceof DTLSConnector) {
                ((DTLSConnector) connector).clearConnectionState();
                return;
            } else {
                throw new IllegalStateException(String.format("clearSecurityContext not implemented for connector %s",
                        connector.getClass().getSimpleName()));
            }
        }
        throw new IllegalStateException(String.format("clearSecurityContext not implemented for endpoint %s",
                endpoint.getClass().getSimpleName()));
    }

    public LwM2mServer getServerIdForRegistrationId(String regId) {
        Map<String, LwM2mServer> registeredServers = getRegisteredServers();
        if (registeredServers != null && !registeredServers.isEmpty()) {
            return registeredServers.get("/rd/" + regId);
        }
        return null;
    }

    public void waitForRegistrationTo(LeshanTestServer server) {
        waitForRegistrationTo(server, 1, TimeUnit.SECONDS);
    }

    public void waitForRegistrationTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onRegistrationStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onRegistrationSuccess(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull(), isNotNull());
        inOrder.verifyNoMoreInteractions();
    }

    public Failure waitForRegistrationFailureTo(LeshanTestServer server) {
        return waitForRegistrationFailureTo(server, 1, TimeUnit.SECONDS);
    }

    public Failure waitForRegistrationFailureTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        final ArgumentCaptor<Exception> cExp = ArgumentCaptor.forClass(Exception.class);
        final ArgumentCaptor<ResponseCode> cCode = ArgumentCaptor.forClass(ResponseCode.class);

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onRegistrationStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onRegistrationFailure(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull(), cCode.capture(), any(), cExp.capture());
        inOrder.verifyNoMoreInteractions();

        return new Failure(cExp.getValue(), cCode.getValue());
    }

    public void waitForUpdateTo(LeshanTestServer server) {
        waitForUpdateTo(server, 1, TimeUnit.SECONDS);
    }

    public void waitForUpdateTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateSuccess(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull());
        inOrder.verifyNoMoreInteractions();
    }

    public void waitForUpdateTimeoutTo(LeshanTestServer server) {
        waitForUpdateTimeoutTo(server, 1, TimeUnit.SECONDS);
    }

    public void waitForUpdateTimeoutTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull());

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateTimeout(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull());
        // if client update timeout, it will retry again then try a register so all events can not be consume by inOrder
        // ...
    }

    public Failure waitForUpdateFailureTo(LeshanTestServer server) {
        return waitForUpdateFailureTo(server, 1, TimeUnit.SECONDS);
    }

    public Failure waitForUpdateFailureTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        final ArgumentCaptor<Exception> cExp = ArgumentCaptor.forClass(Exception.class);
        final ArgumentCaptor<ResponseCode> cCode = ArgumentCaptor.forClass(ResponseCode.class);

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onUpdateFailure(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                notNull(), //
                cCode.capture(), //
                any(), //
                cExp.capture());
        // if client update timeout, it will retry again then try a register so all events can not be consume by inOrder
        // ...
        return new Failure(cExp.getValue(), cCode.getValue());
    }

    public void waitForDeregistrationTo(LeshanTestServer server) {
        waitForDeregistrationTo(server, 1, TimeUnit.SECONDS);
    }

    public void waitForDeregistrationTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onDeregistrationStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onDeregistrationSuccess(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull());
        inOrder.verifyNoMoreInteractions();
    }

    public Failure waitForDeregistrationFailureTo(LeshanTestServer server) {
        return waitForDeregistrationFailureTo(server, 1, TimeUnit.SECONDS);
    }

    public Failure waitForDeregistrationFailureTo(LeshanTestServer server, long timeout, TimeUnit unit) {
        final ArgumentCaptor<Exception> cExp = ArgumentCaptor.forClass(Exception.class);
        final ArgumentCaptor<ResponseCode> cCode = ArgumentCaptor.forClass(ResponseCode.class);
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onDeregistrationStarted(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull());
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onDeregistrationFailure(assertArg( //
                s -> assertTrue(isServerIdentifiedByUri(server, s.getUri()))), //
                isNotNull(), //
                cCode.capture(), //
                any(), //
                cExp.capture());
        inOrder.verifyNoMoreInteractions();
        return new Failure(cExp.getValue(), cCode.getValue());
    }

    public void waitForBootstrapSuccess(LeshanBootstrapServer server, long timeout, TimeUnit unit) {
        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onBootstrapStarted(assertArg( //
                s -> assertThat(server.getEndpoints()) //
                        .filteredOn(ep -> ep.getURI().toString().equals(s.getUri())) //
                        .hasSize(1)), //
                notNull());

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onBootstrapSuccess(assertArg( //
                s -> assertThat(server.getEndpoints()) //
                        .filteredOn(ep -> ep.getURI().toString().equals(s.getUri())) //
                        .hasSize(1)), //
                notNull());
    }

    public Failure waitForBootstrapFailure(LeshanBootstrapServer server, long timeout, TimeUnit unit) {
        final ArgumentCaptor<Exception> cExp = ArgumentCaptor.forClass(Exception.class);
        final ArgumentCaptor<ResponseCode> cCode = ArgumentCaptor.forClass(ResponseCode.class);

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onBootstrapStarted(assertArg( //
                s -> assertThat(server.getEndpoints()) //
                        .filteredOn(ep -> ep.getURI().toString().equals(s.getUri())) //
                        .hasSize(1)), //
                notNull());

        inOrder.verify(clientObserver, timeout(unit.toMillis(timeout)).times(1)).onBootstrapFailure(assertArg( //
                s -> assertThat(server.getEndpoints()) //
                        .filteredOn(ep -> ep.getURI().toString().equals(s.getUri())) //
                        .hasSize(1)), //
                notNull(), //
                cCode.capture(), any(), //
                cExp.capture());
        return new Failure(cExp.getValue(), cCode.getValue());
    }

    private boolean isServerIdentifiedByUri(LeshanTestServer server, String expectedUri) {
        for (LwM2mServerEndpoint endpoint : server.getEndpoints()) {
            EndpointUri endpointURI = endpoint.getURI();
            InetSocketAddress endpointAddr = uriHandler.getSocketAddr(endpointURI);
            if (proxy != null && endpointAddr.equals(proxy.getServerAddress())) {
                EndpointUri proxyUri = uriHandler.replaceAddress(endpointURI, proxy.getClientSideProxyAddress());
                if (proxyUri.toString().equals(expectedUri)) {
                    return true;
                }
            } else {
                if (endpointURI.toString().equals(expectedUri)) {
                    return true;
                }
            }
        }
        return false;
    }

}
