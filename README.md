# nso-mpls-vpn-reconcile
Cisco NSO MPLS VPN service reconciliation example

This is an example of service reconciliation code for the NSO mpls-vpn demo from a bottom-up perspective. The idea is that a service is configured in the network and NSO would need to discover and reconciliate it.

NSO min version: 4.4

The reconciliation logic is located here: https://github.com/NSO-developer/Cisco-NSO-MPLS-VPN-service-reconciliation-example/blob/master/packages/l3vpn/python/action.py

To run it:

1) Build the demo: rogaglia$ make all start

2) Create a VPN demo service: rogaglia$ python create-vpn.py 

Now we will simulate a service that was configured in the network and is not know by NSO. We will do that by deleting the service in NSO with the "no-networking" option.

3) Delete service: 

rogaglia$ ncs_cli -C -u admin<br />
admin@ncs# config<br />
admin@ncs(config)# no vpn l3vpn volvo <br />
admin@ncs(config)# commit no-networking <br />
Commit complete.<br />
admin@ncs(config)#<br />

You will see now that a number of devices are "out-of-sync":<br />
admin@ncs(config)# devices check-sync 

You can see that the service config is in the devices via checking the compare config output in the different devices:<br />
admin@ncs(config)# devices device * compare-config

4) Reconciliate devices: We will do full device reconciliation:<br />
admin@ncs(config)# devices sync-from

Now we can run the reconciliation code to discover service. We will first run it as "dry-run", which means that we want to check what changes would be sent to the devices if the discovered services would be commited:

5) dry-run service discovery code: 

admin@ncs(config)# vpn l3vpn-service-discovery dry-run <br />
status false<br />
message Commit Dry Run Device Changes: <br />
Device: pe0 <br />
route-policy volvo<br />
  pass<br />
 end-policy<br />
!<br />

Commit Dry Run Service Changes: <br />
Operation: MOP_CREATED - KeyPath: /l3vpn:vpn/l3vpn{volvo} - Old Value: None - New Value: None <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/name - Old Value: None - New Value: volvo <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/private/re-deploy-counter - Old Value: None - New Value: 0 <br />
Operation: MOP_CREATED - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0} - Old Value: None - New Value: None <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/id - Old Value: None - New Value: discovered_ce0 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/bandwidth - Old Value: None - New Value: 6000000 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/ce-interface - Old Value: None - New Value: GigabitEthernet0/11 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/as-number - Old Value: None - New Value: 65003 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/ip-network - Old Value: None - New Value: 10.10.1.0/24 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce0}/ce-device - Old Value: None - New Value: ce0 <br />
Operation: MOP_CREATED - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1} - Old Value: None - New Value: None <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/id - Old Value: None - New Value: discovered_ce1 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/bandwidth - Old Value: None - New Value: 6000000 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/ce-interface - Old Value: None - New Value: GigabitEthernet0/11 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/as-number - Old Value: None - New Value: 65001 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/ip-network - Old Value: None - New Value: 10.7.7.0/24<br /> 
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce1}/ce-device - Old Value: None - New Value: ce1 <br />
Operation: MOP_CREATED - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4} - Old Value: None - New Value: None <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/id - Old Value: None - New Value: discovered_ce4 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/bandwidth - Old Value: None - New Value: 6000000 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/ce-interface - Old Value: None - New Value: GigabitEthernet0/18 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/as-number - Old Value: None - New Value: 65002 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/ip-network - Old Value: None - New Value: 10.8.8.0/24<br /> 
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/endpoint{discovered_ce4}/ce-device - Old Value: None - New Value: ce4 <br />
Operation: MOP_VALUE_SET - KeyPath: /l3vpn:vpn/l3vpn{volvo}/route-distinguisher - Old Value: None - New Value: 12345 <br />
<br />
admin@ncs(config)# <br />

Let's take attention to this part of the dry-run output:

**status false**<br />
**message Commit Dry Run Device Changes:** <br />
**Device: pe0** <br />
**route-policy volvo**<br />
  **pass**<br />
 **end-policy**<br />
**!**<br />

The commit of the discovered service should not move forward as it would mean a change in the device config. Note: this behaviour was expressly done by introducing a NED missfunction.

The rest of the text output shows the values of the services that were discovered.

4) Add the pe0 missing config:<br />
admin@ncs(config)# devices device pe0 config cisco-ios-xr:route-policy volvo <br />
admin@ncs(config-rpl)# pass<br />
admin@ncs(config-rpl)# commit<br />
Commit complete.<br />
admin@ncs(config-rpl)# top<br />
admin@ncs(config)#<br />

5) Now we can re-run the reconciliation dry-run:

admin@ncs(config)# vpn l3vpn-service-discovery dry-run <br />
status true<br />
message Commit Dry Run Device Changes: <br />
No Changes <br />

(text omitted)

We can see that now the status is set to "true" and we can move forward with the reconciliation:

6) perform reconciliation
admin@ncs(config)# vpn l3vpn-service-discovery reconciliate  <br />
status true <br />
message  <br />

System message at 2017-06-08 00:43:13... <br />
Commit performed by admin via tcp using . <br />
admin@ncs(config)# <br />

7) Check service and device status:
We can list the service and the refcounts in the device configurations:<br />
admin@ncs# show running-config vpn <br />
admin@ncs# show running-config devices device pe0 | display service-meta-data <br />
# Cisco-NSO-MPLS-VPN-service-reconciliation-example
