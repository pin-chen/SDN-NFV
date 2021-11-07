//Class: 1101 軟體定義網路及網路功能虛擬化 曾建超
//Author: 陳品劭 109550206
//Date: 20211103
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

//
import java.util.Map; //mac table
import java.util.Optional;
//
import com.google.common.collect.Maps;

import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector.Builder;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.DefaultFlowRule;

import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;


//


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

	private final Logger log = LoggerFactory.getLogger(getClass());

	/** Some configurable property. */
	private String someProperty;
	
	//
	protected Map< DeviceId, Map<MacAddress, PortNumber> > mac_table = Maps.newConcurrentMap();
	private int idel_time = 30;
	private int priority = 30;
	private ApplicationId appId;
	//
	private LearningBridgeProcessor bridgeProcessor = new LearningBridgeProcessor();
	//
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;
	
	//
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;
	//


	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.bridge");
		packetService.addProcessor(
			bridgeProcessor, PacketProcessor.director(3)
		);
		packetService.requestPackets(
		    	DefaultTrafficSelector.builder().matchEthType( Ethernet.TYPE_IPV4 ).build(),
		    	PacketPriority.REACTIVE, appId
		);
		packetService.requestPackets(
		    	DefaultTrafficSelector.builder().matchEthType( Ethernet.TYPE_ARP  ).build(),
		    	PacketPriority.REACTIVE, appId
		);
		//cfgService.registerProperties(getClass());
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		flowRuleService.removeFlowRulesById(appId);
        	packetService.removeProcessor(bridgeProcessor);
		//cfgService.unregisterProperties(getClass(), false);
		log.info("Stopped");
	}
	/**
	@Modified
	public void modified(ComponentContext context) {
	Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
	if (context != null) {
	    someProperty = get(properties, "someProperty");
	}
	log.info("Reconfigured");
	}

	@Override
	public void someMethod() {
	log.info("Invoked");
	}
	*/
	
	private class LearningBridgeProcessor implements PacketProcessor {
		@Override
	
		public void process( PacketContext pc ){
			//log.info("aaaaaaaaaaaaaaaaaaaaaa");
			if (pc.isHandled()) return;
			ConnectPoint cp = pc.inPacket().receivedFrom();
			mac_table.putIfAbsent( cp.deviceId(), Maps.newConcurrentMap() );
			actLikeSwitch( pc );
		}

		public void actLikeSwitch( PacketContext pc ){
			InboundPacket inPacket 	= pc.inPacket();
			Ethernet etherFrame 	= inPacket.parsed();
			ConnectPoint cp 	= inPacket.receivedFrom();
			
			//type check
			if (etherFrame.getEtherType() != Ethernet.TYPE_IPV4 && etherFrame.getEtherType() != Ethernet.TYPE_ARP) return;
			
			Map<MacAddress, PortNumber> 	now_mac_table 	= mac_table.get( cp.deviceId() );
			MacAddress 			src 		= etherFrame.getSourceMAC();
			MacAddress			dst 		= etherFrame.getDestinationMAC();
			PortNumber 			outputPort 	= now_mac_table.get( dst );
			
			now_mac_table.put( src, cp.port() );
			
			log.info( "Add MAC address ==> switch {}, MAC: {}, port: {}", cp.deviceId().toString(), src.toString(), now_mac_table.get(src).toString() );

			//miss or FLOOD
			if (outputPort == null){
				pc.treatmentBuilder().setOutput( PortNumber.FLOOD );
				pc.send();
				if(dst.toString().equals("FF:FF:FF:FF:FF:FF") ){ 
					log.info("MAC {}. Flood Packet!", dst.toString() );
					return; //FLOOD
				}
				log.info( "MAC {} is missed on {} ! Flood Packet!", dst.toString(), cp.deviceId().toString() ); //miss
				return;
			}
			
			//hit
			pc.treatmentBuilder().setOutput( outputPort );
			pc.send();

			log.info( "MAC {} is matched on {}! Install flow rule!", dst.toString(), cp.deviceId().toString() );

			//install flow rule
			FlowRule flowRule = DefaultFlowRule.builder()
			.withSelector	( DefaultTrafficSelector.builder().matchEthDst(dst).matchEthSrc(src).build() )
			.withTreatment	( DefaultTrafficTreatment.builder().setOutput(outputPort).build() )
			.withPriority	( priority )
			.withIdleTimeout( idel_time )
			.forDevice	( cp.deviceId() )
			.fromApp	( appId )
			.build		();

			flowRuleService.applyFlowRules( flowRule );
			return;
		}

	}
}
