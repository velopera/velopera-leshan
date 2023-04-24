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
 *     Zebra Technologies - initial API and implementation
 *     Michał Wadowski (Orange) - Improved compliance with rfc6690
 *******************************************************************************/
package org.eclipse.leshan.transport.javacoap.client.request;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.stream.Collectors;

import org.eclipse.leshan.core.link.Link;
import org.eclipse.leshan.core.link.LinkSerializer;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.node.codec.LwM2mEncoder;
import org.eclipse.leshan.core.peer.IpPeer;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.request.BootstrapRequest;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.SendRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.eclipse.leshan.core.request.UplinkRequest;
import org.eclipse.leshan.core.request.UplinkRequestVisitor;

import com.mbed.coap.packet.CoapRequest;
import com.mbed.coap.packet.Opaque;

/**
 * This class is able to create CoAP request from LWM2M {@link UplinkRequest}.
 * <p>
 * Call <code>CoapRequestBuilder#visit(lwm2mRequest)</code>, then get the result using {@link #getRequest()}
 */
public class CoapRequestBuilder implements UplinkRequestVisitor {

    // protected Request coapRequest;
    protected CoapRequest coapRequest;
    protected final IpPeer server;
    protected final LwM2mEncoder encoder;
    protected final LwM2mModel model;
    protected final LinkSerializer linkSerializer;

    public CoapRequestBuilder(IpPeer server, LwM2mEncoder encoder, LwM2mModel model, LinkSerializer linkSerializer) {
        this.server = server;
        this.encoder = encoder;
        this.model = model;
        this.linkSerializer = linkSerializer;

    }

    @Override
    public void visit(BootstrapRequest request) {
        coapRequest = CoapRequest.post("/bs").address(getAddress());

        // Create map of attributes
        HashMap<String, String> attributes = new HashMap<>();
        attributes.putAll(request.getAdditionalAttributes());
        attributes.put("ep", request.getEndpointName());
        if (request.getPreferredContentFormat() != null) {
            attributes.put("pct", Integer.toString(request.getPreferredContentFormat().getCode()));
        }

        // Convert map of attributes in URI Query as String
        String uriQuery = attributes.entrySet().stream() //
                .map(e -> e.getKey() + "=" + e.getValue()) //
                .collect(Collectors.joining("&"));
        coapRequest.options().setUriQuery(uriQuery.toString());

    }

    @Override
    public void visit(RegisterRequest request) {
        coapRequest = CoapRequest.post("/rd").address(getAddress());

        // Create map of attributes
        HashMap<String, String> attributes = new HashMap<>();
        attributes.putAll(request.getAdditionalAttributes());

        attributes.put("ep", request.getEndpointName());

        Long lifetime = request.getLifetime();
        if (lifetime != null)
            attributes.put("lt", lifetime.toString());

        String smsNumber = request.getSmsNumber();
        if (smsNumber != null)
            attributes.put("sms", smsNumber);

        String lwVersion = request.getLwVersion();
        if (lwVersion != null)
            attributes.put("lwm2m", lwVersion);

        EnumSet<BindingMode> bindingMode = request.getBindingMode();
        if (bindingMode != null)
            attributes.put("b", BindingMode.toString(bindingMode));

        Boolean queueMode = request.getQueueMode();
        if (queueMode != null && queueMode)
            attributes.put("Q", null);

        // Convert map of attributes in URI Query as String
        String uriQuery = attributes.entrySet().stream() //
                .map(e -> e.getValue() == null ? e.getKey() : e.getKey() + "=" + e.getValue()) //
                .collect(Collectors.joining("&"));
        coapRequest.options().setUriQuery(uriQuery.toString());

        // Add Object links as Payload
        Link[] objectLinks = request.getObjectLinks();
        if (objectLinks != null) {
            String payload = linkSerializer.serializeCoreLinkFormat(objectLinks);
            coapRequest = coapRequest.payload(Opaque.of(payload), (short) ContentFormat.LINK.getCode());
        }
    }

    @Override
    public void visit(UpdateRequest request) {
        coapRequest = CoapRequest.post(request.getRegistrationId()).address(getAddress());

        // Create map of attributes
        HashMap<String, String> attributes = new HashMap<>();

        Long lifetime = request.getLifeTimeInSec();
        if (lifetime != null)
            attributes.put("lt", lifetime.toString());

        String smsNumber = request.getSmsNumber();
        if (smsNumber != null)
            attributes.put("sms", smsNumber);

        EnumSet<BindingMode> bindingMode = request.getBindingMode();
        if (bindingMode != null)
            attributes.put("b", BindingMode.toString(bindingMode));

        // Convert map of attributes in URI Query as String
        String uriQuery = attributes.entrySet().stream() //
                .map(e -> e.getValue() == null ? e.getKey() : e.getKey() + "=" + e.getValue()) //
                .collect(Collectors.joining("&"));
        coapRequest.options().setUriQuery(uriQuery.toString());

        // Add Object links as Payload
        Link[] linkObjects = request.getObjectLinks();
        if (linkObjects != null) {
            coapRequest = coapRequest.payload(Opaque.of(linkSerializer.serializeCoreLinkFormat(linkObjects)),
                    (short) ContentFormat.LINK.getCode());
        }
    }

    @Override
    public void visit(DeregisterRequest request) {
        coapRequest = CoapRequest.delete(request.getRegistrationId()).address(getAddress());
    }

    @Override
    public void visit(SendRequest request) {
        coapRequest = CoapRequest.post("/dp").address(getAddress());

        ContentFormat format = request.getFormat();
        Opaque payload = Opaque.of(encoder.encodeTimestampedNodes(request.getTimestampedNodes(), format, model));
        coapRequest = coapRequest.payload(payload, (short) format.getCode());
    }

    public CoapRequest getRequest() {
        return coapRequest;
    }

    protected InetSocketAddress getAddress() {
        return server.getSocketAddress();
    }

}
