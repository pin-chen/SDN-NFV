#Class: 1101 軟體定義網路及網路功能虛擬化 曾建超
#Author: 陳品劭 109550206
#Date: 20210925
from mininet.topo import Topo

class Project1_Topo_109550206( Topo ):
    def __init__( self ):
        Topo.__init__( self )

        # Add hosts
        h1 = self.addHost( 'h1', ip = '192.168.0.1/27')
        h2 = self.addHost( 'h2', ip = '192.168.0.2/27' )
	h3 = self.addHost( 'h3', ip = '192.168.0.3/27' )

        # Add switches
        s1 = self.addSwitch( 's1' )
	s2 = self.addSwitch( 's2' )
	s3 = self.addSwitch( 's3' )
	s4 = self.addSwitch( 's4' )        

        # Add links host/switch
        self.addLink( h1, s1 )
        self.addLink( h2, s2 )
	self.addLink( h3, s3 )
	
	# Add links switch/switch
        self.addLink( s1, s4 )
        self.addLink( s2, s4 )
	self.addLink( s3, s4 )
	

topos = { 'topo_part3_109550206': Project1_Topo_109550206 }
