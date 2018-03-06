package com.tailf.packages.ned.ios;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.regex.Pattern;

import com.tailf.maapi.Maapi;
import com.tailf.ncs.NcsMain;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.ResourceManager;
import com.tailf.ncs.ns.Ncs;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.maapi.MaapiConfigFlag;
import com.tailf.maapi.MaapiSchemas;
import com.tailf.maapi.MaapiSchemas.CSNode;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuList;

import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;
import com.tailf.dp.DpUserInfo;
import com.tailf.dp.annotations.DataCallback;
import com.tailf.dp.annotations.TransCallback;
import com.tailf.dp.proto.DataCBType;
import com.tailf.dp.proto.TransCBType;

public class IOSDp {

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;

    private boolean isNetconf(DpTrans trans)
        throws DpCallbackException {

        DpUserInfo uinfo = trans.getUserInfo();
        if ("netconf".equals(uinfo.getContext()))
            return true;

        return false;
    }

    private void interfaceVrDeleteSingle(String vrfname, NavuContainer entry)
        throws Exception, NavuException {
        try {
            // Check: interface * / ip vrf forwarding
            if (vrfname.equals(entry.container("ip-vrf")
                               .container("ip")
                               .container("vrf")
                               .leaf("forwarding")
                               .valueAsString())) {
                entry.container("ip-vrf")
                    .container("ip")
                    .container("vrf")
                    .leaf("forwarding").delete();
            }
            // Check: interface * / vrf forwarding
            if (vrfname.equals(entry.container("vrf")
                               .leaf("forwarding")
                               .valueAsString())) {
                entry.container("vrf").leaf("forwarding").delete();
            }
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    private void interfaceVrDelete(int tid, String path)
        throws Exception, NavuException {
        try {
            int n;
            String[] interfaces = {
                "Embedded-Service-Engine",
                "Ethernet",
                "FastEthernet",
                "GigabitEthernet",
                "TenGigabitEthernet",
                "Loopback",
                "Port-channel",
                "Vlan",
                "Group-Async",
                "Multilink",
                "MFR",
                "Serial",
                "Virtual-Template",
                "LISP",
                "Tunnel"
                // "pseudowire" - does not support below
            };
            String[][] subinterfaces = {
                { "MFR-subinterface", "MFR" },
                { "Port-channel-subinterface", "Port-channel" },
                { "Serial-subinterface", "Serial" },
                { "LISP-subinterface", "LISP" }
            };

            // path=/ncs:devices/device{c7200}/config/ios:ip/vrf{vrfname}
            // /interface/FastEthernet{2/3}/ip-vrf/ip/vrf/forwarding
            // /interface/FastEthernet{2/3}/vrf/forwarding
            NavuContext context = new NavuContext(mm, tid);
            String vrfname   = path.substring(path.lastIndexOf('{')+1);
            vrfname = vrfname.substring(0, vrfname.lastIndexOf('}'));
            String device_id = path.replaceFirst(".*/device\\{(\\S+)\\}/config.*", "$1");

            //System.out.println("interfaceVrDelete dev="+device_id+" vrf="+vrfname);

            // Scan through all interfaces and disable VRF
            for (n = 0; n < interfaces.length; n++) {
                NavuList iflist = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("ios", "interface")
                    .list("ios", interfaces[n]);
                for (NavuContainer entry : iflist.elements()) {
                    //System.out.println("interfaceVrDeleteSingle if="
                    //                 +interfaces[n]
                    //+entry.leaf("name").valueAsString());
                    interfaceVrDeleteSingle(vrfname, entry);
                }

            }
            // Scan through all subinterfaces and disable VRF
            for (n = 0; n < subinterfaces.length; n++) {
                NavuList iflist = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("ios", "interface")
                    .container("ios",subinterfaces[n][0])
                    .list("ios", subinterfaces[n][1]);
                for (NavuContainer entry : iflist.elements()) {
                    //System.out.println("interfaceVrDeleteSingle if="
                    //+subinterfaces[n][1]
                    //+entry.leaf("name").valueAsString());
                    interfaceVrDeleteSingle(vrfname, entry);
                }
            }
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // vrfDefinitionRemove
    @DataCallback(callPoint="vrf-definition-hook",
                  callType=DataCBType.REMOVE)
        public int vrfDefinitionRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();

            //System.out.println("vrfDefinitionRemove() path="+path);
            mm.safeDelete(tid,path.replace("vrf/definition","ipv6/route/vrf"));
            mm.safeDelete(tid,path.replace("vrf/definition","ip/route/vrf"));
            interfaceVrDelete(tid, path);

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // ipVrfRemove
    @DataCallback(callPoint="ip-vrf-hook",
                  callType=DataCBType.REMOVE)
        public int ipVrfRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();

            //System.out.println("ipVrfRemove() path="+path);

            mm.safeDelete(tid,path.replace("ip/vrf","ip/route/vrf"));
            interfaceVrDelete(tid, path);

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    // interfaceSwitchportCreate
    @DataCallback(callPoint="interface-switchport-hook",
            callType=DataCBType.CREATE)
        public int interfaceSwitchportCreate(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int    tid     = trans.getTransaction();
            String path    = new ConfPath(keyPath).toString();
            String toppath = path.substring(0, path.indexOf("interface"));
            String ifpath  = path.replace("switchport", "");
            boolean me340x = false;

            //System.out.println("interfaceSwitchportCreate() path="+path);

            // Look for me340x
            ConfValue val = mm.safeGetElem(tid, toppath+"tailfned/device");
            if (val != null && val.toString().equals("me340x")) {
                //System.out.println("interfaceSwitchportRemove() found ME340X");
                me340x = true;
            }

            // interface * / ip address
            mm.safeDelete(tid, ifpath+"ip/no-address/address");
            mm.safeDelete(tid, ifpath+"ip/address");
            if (me340x == true) {
                // interface * / delete 'ip route-cache' = default = true
                //System.out.println("   switchport -> delete ip route-cache for "+ifpath);
                mm.safeDelete(tid, ifpath+"ip/route-cache-conf/route-cache");
            }

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    // interfaceSwitchportRemove
    @DataCallback(callPoint="interface-switchport-hook",
            callType=DataCBType.REMOVE)
        public int interfaceSwitchportRemove(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            int    tid     = trans.getTransaction();
            String path    = new ConfPath(keyPath).toString();
            String toppath = path.substring(0, path.indexOf("interface"));
            String ifpath  = path.replace("switchport", "");
            boolean me340x  = false;

            //System.out.println("interfaceSwitchportRemove() path="
            //+path+" toppath = "+toppath);

            // Look for me340x
            ConfValue val = mm.safeGetElem(tid, toppath+"tailfned/device");
            if (val != null && val.toString().equals("me340x")) {
                //System.out.println("interfaceSwitchportRemove() found ME340X");
                me340x = true;
            }

            if (me340x == true) {
                // interface * / no ip route-cache
                //System.out.println("   switchport -> 'no ip route-cache' for "+ifpath);
                mm.setElem(tid, "false", ifpath+"ip/route-cache-conf/route-cache");
            }
            // interface * / no ip address
            mm.setElem(tid, "false", ifpath+"ip/no-address/address");

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    // bridgeDomainSet
    @DataCallback(callPoint="UNUSED-bridge-domain-setelem",
            callType=DataCBType.SET_ELEM)
    public int bridgeDomainSet(DpTrans trans, ConfObject[] keyPath,
                                   ConfValue newval)
            throws DpCallbackException {
        try {
            int    tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String toppath  = path.substring(0, path.indexOf("/config/ios:")+12);
            String vlanpath = toppath + "vlan/vlan-list{"+newval.toString()+"}";

            //System.out.println("bridgeDomainSet() path="
            //+path+" id="+newval.toString());

            if (mm.exists(tid, vlanpath) == true) {
                return Conf.REPLY_OK;
            }
            else {
                try {
                    mm.sharedCreate(tid, vlanpath);
                }
                catch (Exception e) {
                    mm.safeCreate(tid, vlanpath);
                }
            }

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    // bridgeDomainCreate
    @DataCallback(callPoint="UNUSED-bridge-domain-create",
            callType=DataCBType.CREATE)
        public int bridgeDomainCreate(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            int    tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String toppath  = path.substring(0, path.indexOf("/config/ios:")+12);
            String id = path.substring(path.lastIndexOf('{')+1);
            id = id.substring(0, id.lastIndexOf('}'));
            String vlanpath = toppath + "vlan/vlan-list{"+id+"}";

            //System.out.println("bridgeDomainCreate() path="+path+" id="+id);

            if (mm.exists(tid, vlanpath) == true) {
                return Conf.REPLY_OK;
            }
            else {
                try {
                    mm.sharedCreate(tid, vlanpath);
                }
                catch (Exception e) {
                    mm.safeCreate(tid, vlanpath);
                }
            }

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // cryptoPkiTrustpointCreate
    @DataCallback(callPoint="crypto-pki-trustpoint-hook",
            callType=DataCBType.CREATE)
        public int cryptoPkiTrustpointCreate(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            int    tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String toppath  = path.substring(0, path.indexOf("/config/ios:")+12);
            String id = path.substring(path.lastIndexOf('{')+1);
            id = id.substring(0, id.lastIndexOf('}'));
            String chainpath = toppath + "crypto/pki/certificate/chain{"+id+"}";

            //System.out.println("cryptoPkiTrustpointCreate() path="+path+" id="+id);

            mm.setElem(tid, "crl", path+"/revocation-check");

            if (mm.exists(tid, chainpath) == true) {
                return Conf.REPLY_OK;
            }
            else {
                try {
                    mm.sharedCreate(tid, chainpath);
                }
                catch (Exception e) {
                    mm.safeCreate(tid, chainpath);
                }
            }

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // cryptoPkiTrustpointRemove
    @DataCallback(callPoint="crypto-pki-trustpoint-hook",
            callType=DataCBType.REMOVE)
        public int cryptoPkiTrustpointRemove(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String toppath  = path.substring(0, path.indexOf("/config/ios:")+12);
            String id = path.substring(path.lastIndexOf('{')+1);
            id = id.substring(0, id.lastIndexOf('}'));
            String chainpath = toppath + "crypto/pki/certificate/chain{"+id+"}";

            //System.out.println("cryptoPkiTrustpointRemove() path="+path+" id="+id);

            mm.safeDelete(tid, chainpath);

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // ipRoutingRemove
    @DataCallback(callPoint="ip-routing-hook",
            callType=DataCBType.REMOVE)
        public int ipRoutingRemove(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int    tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String toppath  = path.substring(0, path.indexOf("/config/ios:")+12);

            //System.out.println("ipRoutingRemove() path="+path);

            mm.safeDelete(tid, toppath + "router/rip");
            mm.safeDelete(tid, toppath + "router/ospf");
            mm.safeDelete(tid, toppath + "router/bgp");
            mm.safeDelete(tid, toppath + "router/mobile");
            mm.safeDelete(tid, toppath + "ip/mobile/router");

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // interfacePortChannelRemove
    @DataCallback(callPoint="interface-port-channel-hook",
            callType=DataCBType.REMOVE)
        public int interfacePortChannelRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            if (isNetconf(trans))
                return Conf.REPLY_OK;

            int    tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            IOSInterface ethif = new IOSInterface(trans);

            //System.out.println("interfacePortChannelRemove() path="+path);

            ethif.ethernetWalk(tid, 0, path);
            ethif.close();

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    // IOSDpInit
    @TransCallback(callType=TransCBType.INIT)
    public void IOSDpInit(DpTrans trans) throws DpCallbackException {

        try {
            if (mm == null) {
                // Need a Maapi socket so that we can attach
                Socket s = new Socket("127.0.0.1", NcsMain.getInstance().
                                      getNcsPort());
                mm = new Maapi(s);
            }
            mm.attach(trans.getTransaction(),0,
                      trans.getUserInfo().getUserId());
            return;
        }
        catch (Exception e) {
            throw new DpCallbackException("Failed to attach", e);
        }
    }


    // IOSDpFinish
    @TransCallback(callType=TransCBType.FINISH)
    public void IOSDpFinish(DpTrans trans) throws DpCallbackException {

        try {
            mm.detach(trans.getTransaction());
        }
        catch (Exception e) {
            ;
        }
    }

}
