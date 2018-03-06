# The order of packages is significant as there are dependencies between
# the packages. Typically generated namespaces are used by other packages.
PACKAGES = l3vpn l3vpnui cisco-ios cisco-iosxr juniper-junos alu-sr

# The create-network argument to ncs-netsim
NETWORK = create-network packages/cisco-ios 9 ce \
		  create-network packages/cisco-iosxr 2 pe \
		  create-network packages/juniper-junos 1 pe \
		  create-network packages/alu-sr 1 pe \
		  create-network packages/cisco-iosxr 4 p

NETSIM_DIR = netsim

all: build-all $(NETSIM_DIR)

build-all:
	for i in $(PACKAGES); do \
		$(MAKE) -C packages/$${i}/src all || exit 1; \
	done

$(NETSIM_DIR): packages/cisco-ios packages/cisco-iosxr packages/juniper-junos
	ncs-netsim --dir netsim $(NETWORK)
	cp initial_data/ios.xml netsim/ce/ce0/cdb
	cp initial_data/ios.xml netsim/ce/ce1/cdb
	cp initial_data/ios.xml netsim/ce/ce2/cdb
	cp initial_data/ios.xml netsim/ce/ce3/cdb
	cp initial_data/ios.xml netsim/ce/ce4/cdb
	cp initial_data/ios.xml netsim/ce/ce5/cdb
	cp initial_data/ios.xml netsim/ce/ce6/cdb
	cp initial_data/ios.xml netsim/ce/ce7/cdb
	cp initial_data/ios.xml netsim/ce/ce8/cdb
	cp initial_data/iosxr.xml netsim/pe/pe0/cdb
	cp initial_data/iosxr.xml netsim/pe/pe1/cdb
	cp initial_data/alu-sr.xml netsim/pe/pe3/cdb
	cp initial_data/iosxr.xml netsim/p/p0/cdb
	cp initial_data/iosxr.xml netsim/p/p1/cdb
	cp initial_data/iosxr.xml netsim/p/p2/cdb
	cp initial_data/iosxr.xml netsim/p/p3/cdb
	cp initial_data/topology.xml ncs-cdb
	cp initial_data/qos.xml ncs-cdb
	cp initial_data/compliance.xml ncs-cdb
	cp initial_data/static-map-pos.xml ncs-cdb
	cp initial_data/template.xml ncs-cdb
	cp initial_data/device-groups.xml ncs-cdb
	cp initial_data/device-icons.xml ncs-cdb
	ncs-netsim ncs-xml-init > ncs-cdb/netsim_devices_init.xml

#Patch to add ios-stats
#packages/cisco-ios:
#	ln -s $(NCS_DIR)/packages/neds/cisco-ios packages/cisco-ios
#
#packages/juniper-junos:
#	ln -s $(NCS_DIR)/packages/neds/juniper-junos packages/juniper-junos
#
#packages/cisco-iosxr:
#	ln -s $(NCS_DIR)/packages/neds/cisco-iosxr packages/cisco-iosxr

clean:
	for i in $(PACKAGES); do \
		$(MAKE) -C packages/$${i}/src clean || exit 1; \
	done
	rm -rf netsim running.DB logs/* state/* ncs-cdb/*.cdb *.trace
#	rm -f packages/cisco-ios
#	rm -f packages/juniper-junos
#	rm -f packages/cisco-iosxr
	rm -rf bin
	rm -rf ncs-cdb/*.xml

start:
	ncs-netsim start
	ncs

stop:
	-ncs-netsim stop
	-ncs --stop

reset:
	ncs-setup --reset

cli:
	ncs_cli -u admin
