/*******************************************************************************
 * Copyright (c) 2022 Sierra Wireless and others.
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
package org.eclipse.leshan.transport.californium.client.endpoint.coap;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.eclipse.californium.core.coap.Message;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.CoapEndpoint.Builder;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.cose.AlgorithmID;
import org.eclipse.californium.cose.CoseException;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.EndpointContext;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.oscore.OSCoreCoapStackFactory;
import org.eclipse.californium.oscore.OSCoreEndpointContextInfo;
import org.eclipse.leshan.client.endpoint.ClientEndpointToolbox;
import org.eclipse.leshan.client.servers.LwM2mServer;
import org.eclipse.leshan.client.servers.ServerInfo;
import org.eclipse.leshan.core.oscore.InvalidOscoreSettingException;
import org.eclipse.leshan.core.oscore.OscoreValidator;
import org.eclipse.leshan.core.peer.IpPeer;
import org.eclipse.leshan.core.peer.LwM2mPeer;
import org.eclipse.leshan.core.peer.OscoreIdentity;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.transport.californium.client.CaliforniumConnectionController;
import org.eclipse.leshan.transport.californium.identity.IdentityHandler;
import org.eclipse.leshan.transport.californium.oscore.cf.InMemoryOscoreContextDB;
import org.eclipse.leshan.transport.californium.oscore.cf.OscoreParameters;
import org.eclipse.leshan.transport.californium.oscore.cf.StaticOscoreStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upokecenter.cbor.CBORObject;

public class CoapOscoreClientEndpointFactory extends CoapClientEndpointFactory {

    private static final Logger LOG = LoggerFactory.getLogger(CoapOscoreClientEndpointFactory.class);

    @Override
    public String getEndpointDescription() {
        return super.getEndpointDescription() + " with very experimental support of OSCORE";
    }

    /**
     * This method is intended to be overridden.
     *
     * @param address the IP address and port, if null the connector is bound to an ephemeral port on the wildcard
     *        address.
     * @param coapConfig the CoAP config used to create this endpoint.
     * @return the {@link Builder} used for unsecured communication.
     */
    @Override
    protected CoapEndpoint.Builder createEndpointBuilder(InetSocketAddress address, ServerInfo serverInfo,
            Configuration coapConfig, ClientEndpointToolbox toolbox) {
        CoapEndpoint.Builder builder = super.createEndpointBuilder(address, serverInfo, coapConfig, toolbox);

        // handle oscore
        if (serverInfo.useOscore) {
            // oscore only mode
            LOG.warn("Experimental OSCORE support is used for {}", serverInfo.getFullUri().toASCIIString());

            try {
                new OscoreValidator().validateOscoreSetting(serverInfo.oscoreSetting);
            } catch (InvalidOscoreSettingException e) {
                throw new RuntimeException(String.format("Unable to create endpoint for %s using OSCORE : Invalid %s.",
                        serverInfo, serverInfo.oscoreSetting), e);
            }

            AlgorithmID hkdfAlg = null;
            try {
                hkdfAlg = AlgorithmID
                        .FromCBOR(CBORObject.FromObject(serverInfo.oscoreSetting.getHkdfAlgorithm().getValue()));
            } catch (CoseException e) {
                throw new RuntimeException(String.format(
                        "Unable to create endpoint for %s using OSCORE : Unable to decode OSCORE HKDF from %s.",
                        serverInfo, serverInfo.oscoreSetting), e);
            }
            AlgorithmID aeadAlg = null;
            try {
                aeadAlg = AlgorithmID
                        .FromCBOR(CBORObject.FromObject(serverInfo.oscoreSetting.getAeadAlgorithm().getValue()));
            } catch (CoseException e) {
                throw new RuntimeException(String.format(
                        "Unable to create endpoint for %s using OSCORE : Unable to decode OSCORE AEAD from %s.",
                        serverInfo, serverInfo.oscoreSetting), e);
            }

            // TODO OSCORE kind of hack because californium doesn't support an empty byte[] array for salt ?
            byte[] masterSalt = serverInfo.oscoreSetting.getMasterSalt().length == 0 ? null
                    : serverInfo.oscoreSetting.getMasterSalt();

            OscoreParameters oscoreParameters = new OscoreParameters(serverInfo.oscoreSetting.getSenderId(),
                    serverInfo.oscoreSetting.getRecipientId(), serverInfo.oscoreSetting.getMasterSecret(), aeadAlg,
                    hkdfAlg, masterSalt);

            InMemoryOscoreContextDB oscoreCtxDB = new InMemoryOscoreContextDB(new StaticOscoreStore(oscoreParameters),
                    true);
            builder.setCustomCoapStackArgument(oscoreCtxDB).setCoapStackFactory(new OSCoreCoapStackFactory());
        }

        LOG.warn("Experimental OSCORE feature is enabled.");

        return builder;
    }

    @Override
    public IdentityHandler createIdentityHandler() {
        return new IdentityHandler() {
            @Override
            public LwM2mPeer getIdentity(Message receivedMessage) {
                EndpointContext context = receivedMessage.getSourceContext();
                InetSocketAddress peerAddress = context.getPeerAddress();
                Principal senderIdentity = context.getPeerIdentity();
                if (senderIdentity == null) {
                    // Build identity for OSCORE if it is used
                    if (context.get(OSCoreEndpointContextInfo.OSCORE_RECIPIENT_ID) != null) {
                        String recipient = context.get(OSCoreEndpointContextInfo.OSCORE_RECIPIENT_ID);

                        return new IpPeer(peerAddress, new OscoreIdentity(Hex.decodeHex(recipient.toCharArray())));
                    }
                    return new IpPeer(peerAddress);
                } else {
                    return null;
                }
            }

            @Override
            public EndpointContext createEndpointContext(LwM2mPeer client, boolean allowConnectionInitiation) {
                // TODO OSCORE : should we add properties to endpoint context ?
                if (client instanceof IpPeer) {
                    return new AddressEndpointContext(((IpPeer) client).getSocketAddress());
                } else {
                    throw new IllegalStateException(String.format("Unsupported Peer : %s", client));
                }
            }
        };
    }

    @Override
    public CaliforniumConnectionController createConnectionController() {
        return new CaliforniumConnectionController() {
            @Override
            public void forceReconnection(Endpoint endpoint, LwM2mServer server, boolean resume) {
                // TODO TL : how to force oscore connection ?
            }
        };
    }
}
