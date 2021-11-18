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
package nctu.winlab.ProxyArp;

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

import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.lang.Iterable;
import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;

import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
	
import org.onosproject.net.host.HostService;
import org.onosproject.net.edge.EdgePortService;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.ARP;
import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;

import java.nio.ByteBuffer;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

	private final Logger log = LoggerFactory.getLogger(getClass());

	private ApplicationId appId;
	
	private ProxyArpProcessor arpProcessor = new ProxyArpProcessor();

	protected Map< Ip4Address, MacAddress > ip_mac_table = Maps.newConcurrentMap();

	protected Map< MacAddress, DeviceId > mac_sId_table = Maps.newConcurrentMap();

	protected Map< MacAddress, PortNumber > mac_port_table = Maps.newConcurrentMap();
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected ComponentConfigService cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected CoreService coreService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected PacketService packetService;
	
	@Reference(cardinality = ReferenceCardinality.MANDATORY)
	protected EdgePortService eptService;

	@Activate
	protected void activate() {
		appId = coreService.registerApplication("nctu.winlab.ProxyArp");
		packetService.addProcessor( arpProcessor, PacketProcessor.director(3) );
		log.info("Started");
	}

	@Deactivate
	protected void deactivate() {
		packetService.removeProcessor(arpProcessor);
		log.info("Stopped");
	}

	private class ProxyArpProcessor implements PacketProcessor {
		@Override
		public void process( PacketContext pc ){
			if (pc.isHandled()) return;
			ProxyArp( pc );
		}

		public void ProxyArp( PacketContext pc ){
			InboundPacket inPacket 	= pc.inPacket();
			ConnectPoint cp 	= inPacket.receivedFrom();
			
			Ethernet etherFrame 	= inPacket.parsed();		
			if( etherFrame.getEtherType() != Ethernet.TYPE_ARP ) return;
			
			ARP arpDatagram		= (ARP) etherFrame.getPayload();
			
			if(arpDatagram.getProtocolType() != ARP.PROTO_TYPE_IP) return;
	
			Ip4Address target = Ip4Address.valueOf(arpDatagram.getTargetProtocolAddress());
			Ip4Address sender = Ip4Address.valueOf(arpDatagram.getSenderProtocolAddress());
			MacAddress dst = etherFrame.getDestinationMAC();
			MacAddress src = etherFrame.getSourceMAC();

			DeviceId sId = cp.deviceId();
			PortNumber inport = cp.port();

			macLearning(sender, src);
			sIdLearning(src, sId);
			portLearning(src, inport);

			if(arpDatagram.getOpCode() == ARP.OP_REQUEST){
				if(ip_mac_table.containsKey(target)){
					Ethernet frame = buildArpPacket(ARP.OP_REPLY, sender, target, src, ip_mac_table.get(target));
					sendFrame(sId, inport, frame);
					log.info("TABLE HIT. Requested MAC = {}", ip_mac_table.get(target));
				}else{
					Ethernet frame = buildArpPacket(ARP.OP_REQUEST, target, sender, dst, src);
					Iterable<ConnectPoint> edgePoints = eptService.getEdgePoints();
					for(ConnectPoint edgePoint : edgePoints){
						if(edgePoint.deviceId().toString().equals(sId.toString()) && edgePoint.port().toString().equals(inport.toString())){
							continue;
						}
						sendFrame(edgePoint.deviceId(), edgePoint.port(), frame);
					}
					log.info("TABLE MISS. Send request to edge ports");
				}
			}

			if(arpDatagram.getOpCode() == ARP.OP_REPLY){
				Ethernet frame = buildArpPacket(ARP.OP_REPLY, target, sender, dst, src);
				sendFrame(mac_sId_table.get(dst), mac_port_table.get(dst), frame);
				log.info("RECV REPLY. Requested MAC = {}", src);
			}			
			
		}

		public void macLearning(Ip4Address ip, MacAddress mac){
			if(ip_mac_table.containsKey(ip)) return;
			ip_mac_table.put(ip, mac);
		}
		
		public void sIdLearning(MacAddress mac, DeviceId sId){
			if(mac_sId_table.containsKey(mac)) return;
			mac_sId_table.put(mac, sId);
		}

		public void portLearning(MacAddress mac, PortNumber port){
			if(mac_port_table.containsKey(mac)) return;
			mac_port_table.put(mac, port);
		}

		public void sendFrame(DeviceId sId, PortNumber output, Ethernet frame){
			TrafficTreatment t = DefaultTrafficTreatment.builder().setOutput(output).build();
			OutboundPacket o = new DefaultOutboundPacket(sId, t, ByteBuffer.wrap(frame.serialize()));
			packetService.emit(o);
		}
		
		public Ethernet buildArpPacket(short op, Ip4Address target, Ip4Address sender, MacAddress dst, MacAddress src){	
			ARP arp = new ARP();
			arp.setOpCode(op);
			arp.setProtocolType(ARP.PROTO_TYPE_IP);
			arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
			arp.setProtocolAddressLength((byte) Ip4Address.BYTE_LENGTH);
			arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);			
			arp.setTargetHardwareAddress(dst.toBytes());
			arp.setSenderHardwareAddress(src.toBytes());
			arp.setTargetProtocolAddress(target.toInt());
			arp.setSenderProtocolAddress(sender.toInt());

			Ethernet eth = new Ethernet();
			eth.setDestinationMACAddress(dst);
			eth.setSourceMACAddress(src);
			eth.setEtherType(Ethernet.TYPE_ARP);
			eth.setPayload(arp);

			return eth;
		}
	}

}
