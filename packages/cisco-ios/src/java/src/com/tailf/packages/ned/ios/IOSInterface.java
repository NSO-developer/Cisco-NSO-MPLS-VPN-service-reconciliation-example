package com.tailf.packages.ned.ios;

import java.net.Socket;

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

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuList;

import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;

import com.tailf.ned.NedException;


public class IOSInterface {

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;
    private DpTrans dpTrans = null;

    int n;
    String[] ethernetInterfaces = {
        "Ethernet",
        "FastEthernet",
        "GigabitEthernet",
        "TenGigabitEthernet"
    };

    public IOSInterface(DpTrans trans)
        throws DpCallbackException {
        try {
            if (mm == null) {
                // Need a Maapi socket so that we can attach
                Socket s = new Socket("127.0.0.1", NcsMain.getInstance().
                                      getNcsPort());
                mm = new Maapi(s);
            }
            dpTrans = trans;
            mm.attach(trans.getTransaction(),0,
                      trans.getUserInfo().getUserId());
            //System.out.println("IOSInterface - init");
            return;
        }
        catch (Exception e) {
            throw new DpCallbackException("failed to attach to Maapi", e);
        }
    }

    public void close()
        throws DpCallbackException {
        //System.out.println("IOSInterface - exit");
        try {
            if (dpTrans != null)
                mm.detach(dpTrans.getTransaction());
        }
        catch (Exception e) {
            ;
        }
    }

    private void channelGroupDelete(String path, NavuContainer entry)
        throws Exception, NavuException {
        try {
            String channelGroupId = entry.container("channel-group")
                .leaf("number")
                .valueAsString();
            if (channelGroupId == null)
                return;

            String id = path.substring(path.lastIndexOf('{')+1);
            id = id.substring(0, id.lastIndexOf('}'));

            if (id.equals(channelGroupId)) {
                //System.out.println("channelGroupDelete("
                //+ethernetInterfaces[n]
                //+entry.leaf("name").valueAsString()
                //+") Port-channel id="+id);
                entry.container("channel-group").leaf("number").delete();
                entry.container("channel-group").leaf("mode").delete();
                entry.leaf("shutdown").safeCreate();
            }
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    public void ethernetWalk(int tid, int type, String path)
        throws Exception, NavuException, DpCallbackException {
        try {
            int n;

            // path=/ncs:devices/device{<device_id>}/config/ios:XXX
            // /interface/FastEthernet{<name>}/XXX
            NavuContext context = new NavuContext(mm, tid);
            String device_id = path.replaceFirst(".*/device\\{(\\S+)\\}/config.*", "$1");

            //System.out.println("ethernetWalk dev="+device_id+" type="+type);

            // Scan through all Ethernet interfaces
            for (n = 0; n < ethernetInterfaces.length; n++) {
                NavuList iflist = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("ios", "interface")
                    .list("ios", ethernetInterfaces[n]);
                for (NavuContainer entry : iflist.elements()) {
                    switch (type) {
                    case 0:
                        channelGroupDelete(path, entry);
                        break;
                    default:
                        break;
                    }
                }

            }
        }
        catch (Exception e) {
            throw new DpCallbackException("ethernetWalk failed", e);
        }
    }


}

