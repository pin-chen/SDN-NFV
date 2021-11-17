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
package nctu.winlab.unicastdhcp;


import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

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

import java.util.Map;
import java.util.Set;
import java.util.Optional;

import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;

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
import org.onosproject.net.topology.PathService;
import org.onosproject.net.Path;
import org.onosproject.net.ElementId;
import org.onosproject.net.Link;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.UDP;
import org.onlab.packet.TpPort;
import org.onlab.packet.DHCP;

////

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

	private final Logger log = LoggerFactory.getLogger(getClass());
	
	private ApplicationId appId;
	
	private final LocationConfigListener cfgListener = new LocationConfigListener();
	private final ConfigFactory factory = new ConfigFactory<ApplicationId, LocationConfig>(APP_SUBJECT_FACTORY, LocationConfig.class, "UnicastDhcpConfig") {
		@Override
		public LocationConfig createConfig() {
			return new LocationConfig();
		}
	};
	
	private DhcpProcessor dhcpProcessor = new DhcpProcessor();
	protected Map< DeviceId, Map<MacAddress, FlowRule> > ToSever_rule_table = Maps.newConcurrentMap();
	protected Map< DeviceId, Map<MacAddress, FlowRule> > ToClient_rule_table = Maps.newConcurrentMap();
	protected DeviceId DHCPvS;
	protected PortNumber DHCPvSP;

	private int idle_time = 30;
	private int priority = 30;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected NetworkConfigRegistry ncfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected FlowRuleService flowRuleService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PathService pathService;

	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
		ncfgService.addListener(cfgListener);
		ncfgService.registerConfigFactory(factory);
		packetService.addProcessor( dhcpProcessor, PacketProcessor.director(3) );
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		ncfgService.removeListener(cfgListener);
    		ncfgService.unregisterConfigFactory(factory);
		flowRuleService.removeFlowRulesById(appId);
        	packetService.removeProcessor(dhcpProcessor);
		log.info("Stopped");
	}

	private class LocationConfigListener implements NetworkConfigListener {
		@Override
		public void event(NetworkConfigEvent event) {
			if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) && event.configClass().equals(LocationConfig.class)) {
				LocationConfig config = ncfgService.getConfig(appId, LocationConfig.class);
				if (config != null) {
					String[] splitted = config.name().split("/");
					DHCPvS = DeviceId.deviceId(splitted[0]);
					DHCPvSP = PortNumber.portNumber(splitted[1]);
					request();
					log.info("DHCP sever is at {}", config.name());
				}
			}
		}
		public void request() {
			packetService.requestPackets(
			    	DefaultTrafficSelector.builder()
					.matchEthType( Ethernet.TYPE_IPV4 )
					.matchIPProtocol(IPv4.PROTOCOL_UDP)
					.matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
					.matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
					.build(),
			    	PacketPriority.REACTIVE, appId
			);
			packetService.requestPackets(
			    	DefaultTrafficSelector.builder()
					.matchEthType( Ethernet.TYPE_IPV4 )
					.matchIPProtocol(IPv4.PROTOCOL_UDP)
					.matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
					.matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
					.build(),
			    	PacketPriority.REACTIVE, appId
			);
		}
	}
	////

	private class DhcpProcessor implements PacketProcessor {
		@Override
		public void process( PacketContext pc ){
			if (pc.isHandled()) return;
			actLikeSwitch( pc );
		}

		public void actLikeSwitch( PacketContext pc ){
			InboundPacket inPacket 	= pc.inPacket();
			ConnectPoint cp 	= inPacket.receivedFrom();
			
			Ethernet etherFrame 	= inPacket.parsed();		
			if( etherFrame.getEtherType() != Ethernet.TYPE_IPV4 ) return;

			IPv4 ipDatagram		= (IPv4) etherFrame.getPayload();
			if( ipDatagram.getProtocol() != IPv4.PROTOCOL_UDP ) return;

			UDP udpSegment		= (UDP) ipDatagram.getPayload();
			if( !(udpSegment.getSourcePort() == UDP.DHCP_CLIENT_PORT && udpSegment.getDestinationPort() == UDP.DHCP_SERVER_PORT) && !(udpSegment.getSourcePort() == UDP.DHCP_SERVER_PORT && udpSegment.getDestinationPort() == UDP.DHCP_CLIENT_PORT)) return;

			DHCP dhcpPacket		= (DHCP) udpSegment.getPayload();

			MacAddress src = etherFrame.getSourceMAC();
			MacAddress dst = etherFrame.getDestinationMAC();

			if( dhcpPacket.getPacketType() == DHCP.MsgType.DHCPOFFER){
				if( !ToClient_rule_table.containsKey(cp.deviceId()) ) return;
				//log.info("11111");
				Map<MacAddress, FlowRule> temp = ToClient_rule_table.get(cp.deviceId());
				//log.info("dst {}", dst);
				//log.info("map {}", temp.containsKey(dst));
				if( !temp.containsKey(dst) ) return;
				//log.info("flow rule {}", temp.get(dst).toString());
				flowRuleService.applyFlowRules( temp.get(dst) );
				return;
			}
			if( dhcpPacket.getPacketType() == DHCP.MsgType.DHCPREQUEST){
				pathCompute( pc, cp, src );
				return;
			}
			if( dhcpPacket.getPacketType() == DHCP.MsgType.DHCPACK){
				if( !ToClient_rule_table.containsKey(cp.deviceId()) ) return;
				//log.info("33333");
				Map<MacAddress, FlowRule> temp = ToClient_rule_table.get(cp.deviceId());
				if( !temp.containsKey(dst) ) return;
				flowRuleService.applyFlowRules( temp.get(dst) );
				return;
			}
			if( dhcpPacket.getPacketType() == DHCP.MsgType.DHCPDISCOVER){
				pathCompute( pc, cp, src );
				return;
			}
		}


		public void pathCompute( PacketContext pc, ConnectPoint cp, MacAddress client ){
			//log.info("aaaaa");
			PortNumber ToseverPort = cp.port();
			PortNumber ToclientPort = cp.port();
			
			if(cp.deviceId().toString().equals(DHCPvS.toString())){
				flowRule3( DHCPvSP, ToclientPort, cp.deviceId(), client );
				flowRule4( ToclientPort, DHCPvSP, cp.deviceId(), client );
				return;
			}

			Set<Path> paths = pathService.getPaths(cp.deviceId(), DHCPvS);
			for(Path path: paths){
				if(path.links() != null && !path.links().isEmpty()){
					ToseverPort = path.links().get(0).src().port();
					break;
				}
			}
			
			pc.treatmentBuilder().setOutput( ToseverPort );
			pc.send();
			flowRule3( ToseverPort, ToclientPort, cp.deviceId(), client );
			flowRule4( ToclientPort, ToseverPort, cp.deviceId(), client );
		}


		public void flowRule3( PortNumber output, PortNumber input, DeviceId sId, MacAddress client ){
			FlowRule flowRule = DefaultFlowRule.builder()
			.withSelector	( DefaultTrafficSelector.builder().matchInPort(input).matchEthSrc(client).build() )
			.withTreatment	( DefaultTrafficTreatment.builder().setOutput(output).build() )
			.withPriority	( priority )
			.withIdleTimeout( idle_time )
			.forDevice	( sId )
			.fromApp	( appId )
			.build		();
			
			if(ToSever_rule_table.containsKey(sId)){
				Map<MacAddress, FlowRule> temp = ToSever_rule_table.get(sId);
				temp.put(client, flowRule);
			}else{
				Map<MacAddress, FlowRule> temp = Maps.newConcurrentMap();
				temp.put(client, flowRule);
				ToSever_rule_table.put(sId, temp);
			}
			flowRuleService.applyFlowRules( flowRule );
		}

		public void flowRule4( PortNumber output, PortNumber input, DeviceId sId, MacAddress client ){
			FlowRule flowRule = DefaultFlowRule.builder()
			.withSelector	( DefaultTrafficSelector.builder().matchInPort(input).matchEthDst(client).build() )
			.withTreatment	( DefaultTrafficTreatment.builder().setOutput(output).build() )
			.withPriority	( priority )
			.withIdleTimeout( idle_time )
			.forDevice	( sId )
			.fromApp	( appId )
			.build		();
			if(ToClient_rule_table.containsKey(sId)){
				Map<MacAddress, FlowRule> temp = ToClient_rule_table.get(sId);
				temp.put(client, flowRule);
			}else{
				Map<MacAddress, FlowRule> temp = Maps.newConcurrentMap();
				temp.put(client, flowRule);
				ToClient_rule_table.put(sId, temp);
			}
			flowRuleService.applyFlowRules( flowRule );
		}

	}

}
//tcpdump udp port 67 or port 68
