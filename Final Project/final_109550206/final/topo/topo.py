#!/usr/bin/python

from mininet.topo import Topo
from mininet.net import Mininet
from mininet.log import setLogLevel
from mininet.node import RemoteController
from mininet.cli import CLI
from mininet.node import Node
from mininet.link import TCLink

class MyTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )

        h1 = self.addHost('h1', ip='10.0.2.100/16', mac='ea:e9:78:fb:fd:01')
        h2 = self.addHost('h2', ip='0.0.0.0', mac='ea:e9:78:fb:fd:02')
        h3 = self.addHost('h3', ip='0.0.0.0', mac='ea:e9:78:fb:fd:03')
	h4 = self.addHost('h4', ip='10.0.3.100/16', mac='ea:e9:78:fb:fd:04')
        h5 = self.addHost('h5', ip='10.0.3.101/16', mac='ea:e9:78:fb:fd:05')

        s1 = self.addSwitch('s1')
        s2 = self.addSwitch('s2')
        s3 = self.addSwitch('s3')

        self.addLink(s1, h1)
        self.addLink(s1, h2)
	self.addLink(s1, h3)
        self.addLink(s3, h4)
        self.addLink(s3, h5)
        self.addLink(s1, s2)
        self.addLink(s2, s3)

def run():
    topo = MyTopo()
    net = Mininet(topo=topo, controller=None, link=TCLink)
    net.addController('c0', controller=RemoteController, ip='127.0.0.1', port=6653)

    net.start()

    print("[+] Run DHCP server")
    dhcp = net.getNodeByName('h1')
    # dhcp.cmdPrint('service isc-dhcp-server restart &')
    dhcp.cmdPrint('/usr/sbin/dhcpd 4 -pf /run/dhcp-server-dhcpd.pid -cf ./dhcpd.conf %s' % dhcp.defaultIntf())

    CLI(net)
    print("[-] Killing DHCP server")
    dhcp.cmdPrint("kill -9 `ps aux | grep h5-eth0 | grep dhcpd | awk '{print $2}'`")
    net.stop()

if __name__ == '__main__':
    setLogLevel('info')
    run()
