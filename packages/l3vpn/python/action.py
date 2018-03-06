# -*- mode: python; python-indent: 4 -*-
import ncs
import re
import ipaddress
from ncs.dp import Action

OPER = {
    1: 'MOP_CREATED',
    2: 'MOP_DELETED',
    3: 'MOP_MODIFIED',
    4: 'MOP_VALUE_SET',
    5: 'MOP_MOVED_AFTER',
    6: 'MOP_ATTR_SET'
}

class DiffIterator(object):
    def __init__(self):
         self.count = 0
         self.changes = []
    def __call__(self, kp, op, oldv, newv):
        info = {
            "kp": str(kp),
            "op": OPER[op],
            "oldv": oldv,
            "newv": newv
        }
        self.changes.append(info)
        self.count += 1
        return ncs.ITER_RECURSE

# ---------------
# ACTIONS EXAMPLE
# ---------------
class Action(Action):     
    def compare(self):
        print("Diff set:")
        self.diff_iterate(DiffIterator(), ncs.ITER_WANT_ATTR)
    
    def get_rd(self,root,service_name,asn):
        junos_cap = "http://xml.juniper.net/xnm/1.1/xnm"
        iosxr_cap = "http://tail-f.com/ned/cisco-ios-xr"
        alu_cap = "http://tail-f.com/ned/alu-sr"
        
        pe_names=root.ncs__devices.device_group['PE'].device_name
        
        for device_name in pe_names:
            self.log.info("Analysing PE %s" % device_name)
            device = root.ncs__devices.device[device_name]
            rd = None
            if junos_cap in device.capability:
                rd = self.get_rd_junos(device, service_name)
            if iosxr_cap in device.capability:
                rd = self.get_rd_xr(device, service_name)
            if alu_cap in device.capability:
                rd = self.get_rd_alu(device, asn)
            
            if rd:
                self.log.info("get_rd return: %s" % rd)
                return rd
        
        self.log.info("get_rd return: %s" % str(rd))
        return rd
    
    def get_rd_junos(self, device, service_name):
        try: 
            if service_name in device.config.junos__configuration.\
                    routing_instances.instance:
                return device.config.junos__configuration.routing_instances.\
                    instance[service_name].route_distinguisher.rd_type.split(':')[0] 
        
            return None
        except Exception as e:
            return None

    def get_rd_xr(self, device, service_name):
        try:
            router_bgp = device.config.cisco_ios_xr__router.bgp.bgp_no_instance['100']
            
            if service_name in router_bgp.vrf:
                return router_bgp.vrf[service_name].rd.split(':')[0]    
            
            return None
        except Exception as e:
            return None
         
    def get_rd_alu(self, device, asn):
        try:      
            if asn in device.config.alu__service.vprn:
                rd = device.config.alu__service.vprn[asn].route_distinguisher
                return rd.split(':')[0]
            
            return None
        
        except Exception as e:
            return None
    
    @Action.action    
    def cb_action(self, uinfo, name, kp, input, output):
        output.message = ""
        output.status = False
        
        # First I create the service
        
        changed_services = []
        
        try:
            with ncs.maapi.Maapi() as m:
                with ncs.maapi.Session(m,uinfo.username,uinfo.clearpass):
                    with m.start_write_trans() as t:
                        root = ncs.maagic.get_root(t)
                        
                        # I will first get the list of all CPEs
                        cpe_names=root.ncs__devices.device_group['C'].device_name
                        
                        for cpe in cpe_names:
                            self.log.info("Analysing CPE: " + str(cpe))
                            cpe_device = root.ncs__devices.device[cpe]
                            
                            # I will now check if bgp exist and if
                            # if exits, it belogns to a VPN
                            if(cpe_device.config.ios__router.bgp):
                                endpoint_name = "discovered_%s" % cpe
                                self.log.info("BGP Found")
                                cpe_bgp = cpe_device.config.ios__router.bgp
                                
                                for asn in cpe_bgp:
                                    endpoint_asn=asn.as_no
                                    
                                    for neighbor in asn.neighbor:
                                        cpe_neighbor = neighbor.id
                                
                                # Now I will check if policy is been configured
                                if cpe_device.config.ios__policy_map:
                                    policy_maps = cpe_device.config.ios__policy_map
                                    
                                    choices = ['BRONZE', 'SILVER', 'GOLD']
                                    
                                    for policy_map in policy_maps:
                                        if policy_map.name in choices:
                                            endpoint_qos = policy_map.name
                                        else:
                                            service_name = policy_map.name
                                            endpoint_bandwidth = policy_map.ios__class['class-default'].shape.average.bit_rate
                                
                                for interface in cpe_device.config.ios__interface.GigabitEthernet:
                                    if (interface.description == "%s local network" % service_name):
                                        endpoint_interface = "GigabitEthernet%s" % interface.name
                                        int_address= interface.ip.address.primary.address
                                        int_mask = interface.ip.address.primary.mask
                                        interface = ipaddress.IPv4Interface(unicode('%s/%s' % (int_address,int_mask), "utf-8"))
                                        endpoint_network = str(interface.network)
                                
                                service_rd = self.get_rd(root,service_name,endpoint_asn)
                                
                                changed_services.append(service_name)
                                
                                # Now I can go and create the endpoint
                                
                                if service_name not in root.l3vpn__vpn.l3vpn:
                                    root.l3vpn__vpn.l3vpn.create(service_name)
                                
                                service = root.l3vpn__vpn.l3vpn[service_name]
                                service.route_distinguisher = service_rd
                                
                                # This should be different
                                
                                if endpoint_name not in service.endpoint:
                                    service.endpoint.create(endpoint_name)
                                
                                endpoint = service.endpoint[endpoint_name]
                                endpoint.as_number = endpoint_asn
                                endpoint.bandwidth = endpoint_bandwidth
                                endpoint.ce_device = cpe
                                endpoint.ce_interface = endpoint_interface
                                endpoint.ip_network = endpoint_network
                        

                        if input.dry_run:
                            # now lets see what I want to perform the commit dry-run
                            # I use native format to detect changes in device
                            input_dr = root.ncs__services.commit_dry_run.get_input()
                            input_dr.outformat = 'native'
                            dry_output = root.ncs__services.commit_dry_run(input_dr)
                            
                            output.message += "Commit Dry Run Device Changes: \n"
                            # Let me check that no device will be modified:
                            
                            if len(dry_output.native.device) == 0:
                                output.status = True
                                output.message += "No Changes \n"
                            else:
                                for device in dry_output.native.device:
                                    output.message += "Device: %s \n" % device.name
                                    output.message += str(device.data)
                                    output.message += "\n"
                            
                            output.message += "Commit Dry Run Service Changes: \n"
                            myiter = DiffIterator()
                            m.diff_iterate(t.th,myiter,ncs.ITER_WANT_ATTR)
                            for item in myiter.changes:
                                op = item["op"]
                                kp = item["kp"]
                                oldv = item["oldv"]
                                newv = item["newv"]
                                output.message += "Operation: %s - KeyPath: %s - Old Value: %s - New Value: %s \n" % (op,kp,oldv,newv)
                    
                            return
                        
                        # I now apply changes
                        t.apply()
                        
                        # If requested, I will reconciliate only my l3VPN services
                        # I may need to reconciliate more services
                        
                        if input.reconciliate:
                            self.log.info("Entering reconciliation")
                            services = root.l3vpn__vpn.l3vpn
                            
                            for service_tbd in changed_services:
                                service = services[service_tbd]
                                redeploy_inputs = service.re_deploy.get_input()
                                redeploy_inputs.reconcile.create()
                                redeploy_outputs = service.re_deploy(redeploy_inputs)
                                           
                        output.status = True
                        
        except Exception as e:
            self.log.error("Exception...")
            raise 
              
# ---------------------------------------------
# COMPONENT THREAD THAT WILL BE STARTED BY NCS.
# ---------------------------------------------
class Main(ncs.application.Application):
    def setup(self):
        # The application class sets up logging for us. Is is accessible
        # through 'self.log' and is a ncs.log.Log instance.
        self.log.info('L3VPN discovery main action RUNNING')

        # When using actions, this is how we register them:
        #
        self.register_action('l3vpn-service-discovery', Action)

        # If we registered any callback(s) above, the Application class
        # took care of creating a daemon (related to the service/action point).

        # When this setup method is finished, all registrations are
        # considered done and the application is 'started'.

    def teardown(self):
        # When the application is finished (which would happen if NCS went
        # down, packages were reloaded or some error occurred) this teardown
        # method will be called.

        self.log.info('L3VPN discovery action FINISHED')
