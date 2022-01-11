/*
 * Copyright 2022-present Open Networking Foundation
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
package nctu.winlab.vlanbasedsr;

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
import org.onlab.packet.IpPrefix;
import org.onlab.packet.VlanId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;

import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;
import org.onosproject.net.edge.EdgePortService;

import java.nio.ByteBuffer;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

	private final Logger log = LoggerFactory.getLogger(getClass());
	
	private ApplicationId appId;
	
	private final LocationConfigListener cfgListener = new LocationConfigListener();
	private final ConfigFactory factory = new ConfigFactory<ApplicationId, LocationConfig>(APP_SUBJECT_FACTORY, LocationConfig.class, "vlan") {
		@Override
		public LocationConfig createConfig() {
			return new LocationConfig();
		}
	};
	
	protected Map< DeviceId, IpPrefix > EdgeSwitchSubnet_table = Maps.newConcurrentMap();
	protected Map< DeviceId, VlanId > SwitchVlanId_table = Maps.newConcurrentMap();
	protected Map< ConnectPoint, MacAddress > CPMac_table = Maps.newConcurrentMap();

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
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PathService pathService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected HostService hostService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected EdgePortService eptService;

	@Activate
	protected void activate() {
		//cfgService.registerProperties(getClass());
		appId = coreService.registerApplication("nctu.winlab.vlanbasedsr");
		ncfgService.addListener(cfgListener);
		ncfgService.registerConfigFactory(factory);	
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		ncfgService.removeListener(cfgListener);
    		ncfgService.unregisterConfigFactory(factory);
		flowRuleService.removeFlowRulesById(appId);
		//cfgService.unregisterProperties(getClass(), false);
		log.info("Stopped");
	}

	private class LocationConfigListener implements NetworkConfigListener {
		@Override
		public void event(NetworkConfigEvent event) {
			if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) && event.configClass().equals(LocationConfig.class)) {
				LocationConfig config = ncfgService.getConfig(appId, LocationConfig.class);
				if (config != null) {
					EdgeSwitchSubnet_table.clear();
					SwitchVlanId_table.clear();
					JsonNode vlanconfig = config.node();
					JsonNode vlaninfo = vlanconfig.findValue("info");
					JsonNode subnet = vlaninfo.findValue("subnet");
					Iterator<String> keys = subnet.fieldNames();
					while(keys.hasNext()){
						String device = keys.next();
						String ipprefix = subnet.findValue(device).textValue();
						String[] splitted = ipprefix.split("/");
						IpAddress ip = IpAddress.valueOf(splitted[0]);
						int prefix = Integer.valueOf(splitted[1]);
						DeviceId sw = DeviceId.deviceId(device);
						EdgeSwitchSubnet_table.put(sw, IpPrefix.valueOf(ip, prefix));
					}
					JsonNode id = vlaninfo.findValue("id");
					Iterator<String> keys2 = id.fieldNames();
					while(keys2.hasNext()){
						String device = keys2.next();
						String tag = id.findValue(device).textValue();
						DeviceId sw = DeviceId.deviceId(device);
						SwitchVlanId_table.put(sw, VlanId.vlanId(tag));
					}
					JsonNode host = vlaninfo.findValue("host");
					Iterator<String> keys3 = host.fieldNames();
					while(keys3.hasNext()){
						String cp = keys3.next();
						String mac = host.findValue(cp).textValue();
						CPMac_table.put(ConnectPoint.deviceConnectPoint(cp), MacAddress.valueOf(mac));
					}
					segmentRouting();
					popVlanId();
				}
			}
		}
		public void segmentRouting() {
			for(DeviceId srcD : EdgeSwitchSubnet_table.keySet() ){
				for(DeviceId dstD : EdgeSwitchSubnet_table.keySet()){
					if(srcD.toString().equals( dstD.toString() ) ) continue;
					Set<Path> paths = pathService.getPaths(srcD, dstD);
					for(Path path: paths){
						if(path.links() != null && !path.links().isEmpty()){
							for(Link link : path.links()){
								PortNumber outputPort = link.src().port();
								DeviceId device = link.src().deviceId();
								if(device.toString().equals(srcD.toString())){
									ForwardingObjective Fo = DefaultForwardingObjective.builder()
										.withSelector	( DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchIPDst( EdgeSwitchSubnet_table.get(dstD) ).build() )
										.withTreatment	( DefaultTrafficTreatment.builder().pushVlan().setVlanId( SwitchVlanId_table.get(dstD) ).setOutput(outputPort).build() )
										.withPriority	( priority )
										.withFlag (ForwardingObjective.Flag.VERSATILE)
										.fromApp	( appId )
										.add		();
									flowObjectiveService.forward( device, Fo );
									continue;
								}
								ForwardingObjective Fo = DefaultForwardingObjective.builder()
									.withSelector	( DefaultTrafficSelector.builder().matchVlanId( SwitchVlanId_table.get(dstD) ).build() )
									.withTreatment	( DefaultTrafficTreatment.builder().setOutput(outputPort).build() )
									.withPriority	( priority )
									.withFlag (ForwardingObjective.Flag.VERSATILE)
									.fromApp	( appId )
									.add		();
								flowObjectiveService.forward( device, Fo );
							}
						}
					}
				}
			}
			
					
		}

		public void popVlanId() {
			for(ConnectPoint cp : CPMac_table.keySet()) {
				PortNumber outputPort = cp.port();
				DeviceId device = cp.deviceId();
				ForwardingObjective Fo = DefaultForwardingObjective.builder()
					.withSelector	( DefaultTrafficSelector.builder().matchVlanId( SwitchVlanId_table.get(device) ).matchEthDst( CPMac_table.get(cp) ).build() )
					.withTreatment	( DefaultTrafficTreatment.builder().popVlan().setOutput(outputPort).build() )
					.withPriority	( priority )
					.withFlag (ForwardingObjective.Flag.VERSATILE)
					.fromApp	( appId )
					.add		();
				flowObjectiveService.forward( device, Fo );
				ForwardingObjective Fo1 = DefaultForwardingObjective.builder()
					.withSelector	( DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchIPSrc( EdgeSwitchSubnet_table.get(device) ).matchEthDst( CPMac_table.get(cp) ).build() )
					.withTreatment	( DefaultTrafficTreatment.builder().setOutput(outputPort).build() )
					.withPriority	( priority )
					.withFlag (ForwardingObjective.Flag.VERSATILE)
					.fromApp	( appId )
					.add		();
				flowObjectiveService.forward( device, Fo1 );
			}
		}
		
	}
}
