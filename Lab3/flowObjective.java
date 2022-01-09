/*
 * Copyright 2021-present Open Networking Foundation
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
package nctu.winlab.bridge;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Dictionary;
import java.util.Properties;
import static org.onlab.util.Tools.get;

/* Import Libs*/
import com.google.common.collect.Maps; //Provided ConcurrentMap Implementation
import org.onosproject.core.ApplicationId; // Application Identifier
import org.onosproject.core.CoreService;

// Gain Information about existed flow rules & 
// Injecting flow rules into the environment
import org.onosproject.net.flow.FlowRuleService;

// Selector Entries
// import org.onosproject.net.flow.TrafficSelector;    // Abstraction of a slice of network traffic
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector.Builder;

// Processing packets
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;

// Informations used in API
import org.onlab.packet.Ethernet; // Ethernet Packet
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint; // connect point (including information about )
import org.onosproject.net.DeviceId;    // Representing device identity
import org.onosproject.net.PortNumber; // Represening a port number

// Adding Flow Rule
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.DefaultFlowRule;
// FlowObjective Service
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;

import java.util.Map; // use on building MacTable
import java.util.Optional; // use to specify if it is nullable


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

    /** Some configurable property. */
    private String someProperty;

    // Communicate with the center of controller
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    // Request and emit packets
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    // Apply, Modify Flow Rules
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;


    private final Logger log = LoggerFactory.getLogger(getClass());
    private int IdleTimeOut = 30;
    private int flowRulePriority = 30;
    private ApplicationId appId;
    protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = Maps.newConcurrentMap();

    /* Proicessor of packets*/
    private LearningBridgeProcessor bridgeProcessor = new LearningBridgeProcessor();


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");

        // add processor and Requesting ICMP, ARP packets
        packetService.addProcessor (
            bridgeProcessor, 
            PacketProcessor.director(3) // Priority, high number will be processing first
        );
        requestPackets();

        log.info("Started Learining Bridge.");
    }

    @Deactivate
    protected void deactivate() {
        flowRuleService.removeFlowRulesById(appId); // remove flow rule by application ID
        packetService.removeProcessor(bridgeProcessor); // remove processor
        log.info("Stopped Learning Bridge.");
    }


    // Select ICMP and ARP packets
    void requestPackets(){
        packetService.requestPackets(
            DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(),
            PacketPriority.REACTIVE, appId);
        packetService.requestPackets(
            DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_ARP).build(),
            PacketPriority.REACTIVE, appId);
    }

    // Learing Bridge Processor
    private class LearningBridgeProcessor implements PacketProcessor {
        @Override
        public void process (PacketContext pc){ // process inbound packets
            if (pc.isHandled()) return; // stop when meeeting handled packets 
            if (pc.inPacket().parsed().getEtherType() != Ethernet.TYPE_IPV4 &&
                pc.inPacket().parsed().getEtherType() != Ethernet.TYPE_ARP)
                    return;
            // Base Packet information
            InboundPacket inPacket = pc.inPacket();
            Ethernet etherFrame = inPacket.parsed();
            ConnectPoint cp = inPacket.receivedFrom();
            MacAddress src = etherFrame.getSourceMAC(),
                       dst = etherFrame.getDestinationMAC();

            // If the device havent been put in MacTables, add it;
            macTables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());

            // get mactable of current device (via deviceId)
            Map<MacAddress, PortNumber> currentMacTable = macTables.get(cp.deviceId());
            PortNumber outputPort = currentMacTable.get(dst);

            currentMacTable.put(src, cp.port());    // record in map
            log.info("Add MAC address ==> switch: {}, MAC: {}, port {}", 
                cp.deviceId(), src.toString(), cp.port().toString());

            if (outputPort == null){
                floodToAllPorts(pc);
                log.info("MAC {} is missed on {}! Flood packet!", dst, cp.deviceId());
            }
            else{
                // send to output port
                pc.treatmentBuilder().setOutput(outputPort);
                pc.send();
                log.info("MAC {} is matched on {}! Install flow rule!", dst, outputPort.toString());

                // Forwarding Objective
                ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                    .withSelector(
                        DefaultTrafficSelector.builder()
                        .matchEthSrc(src)
                        .matchEthDst(dst)
                        .build()
                    )
                    .withTreatment(
                        DefaultTrafficTreatment.builder()
                        .setOutput(outputPort)
                        .build()
                    )
                    .withPriority(flowRulePriority)
                    .makeTemporary(IdleTimeOut)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .add();
                flowObjectiveService.forward(cp.deviceId(), forwardingObjective);
            }
        }

        /**
            Floods packet out of all switch ports
            @param pc: PacketContext
         */
        public void floodToAllPorts(PacketContext pc){
            pc.treatmentBuilder().setOutput(PortNumber.FLOOD); 
            pc.send();
        }
    }
}