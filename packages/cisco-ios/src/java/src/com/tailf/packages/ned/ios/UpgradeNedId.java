package com.tailf.packages.ned.ios;

import java.net.Socket;

import com.tailf.cdb.Cdb;
import com.tailf.cdb.CdbDBType;
import com.tailf.cdb.CdbSession;
import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfIdentityRef;
import com.tailf.maapi.Maapi;

public class UpgradeNedId {

    static private final int OLD_IOS_NS_HASH = 539211867;
    static private final int OLD_IOS_HASH = 937667110;
    public UpgradeNedId() {

    }

    public static void main(String[] args) throws Exception {
        try {
            String host = System.getProperty("host");
            String port = System.getProperty("port");

            int iport = Conf.NCS_PORT;
            if(host == null) {
                host = "127.0.0.1";
            }
            if(port != null) {
                iport = Integer.parseInt(port);
            }

            Socket s1 = new Socket(host, iport);
            Cdb cdb = new Cdb("cdb-upgrade-sock", s1);
            cdb.setUseForCdbUpgrade();
            CdbSession cdbsess = cdb.startSession(CdbDBType.CDB_RUNNING);

            Socket s2 = new Socket(host, iport);
            Maapi maapi = new Maapi(s2);
            int th = maapi.attachInit();

            ConfIdentityRef newid = new ConfIdentityRef("urn:ios-id",
                                                        "cisco-ios");

            int no = cdbsess.getNumberOfInstances("/devices/device");
            for(int i = 0; i < no; i++) {
                Integer offset = new Integer(i);

                ConfBuf nameBuf = (ConfBuf)
                    cdbsess.getElem("/devices/device[%d]/name", offset);

                ConfIdentityRef id = (ConfIdentityRef)
                    cdbsess.getElem(
                        "/devices/device[%d]/device-type/cli/ned-id",
                        offset);
                System.out.println("old ned id...:" + id);
                System.out.println("new ned id...:" + newid);

                if (id != null && id.getNSHash() == OLD_IOS_NS_HASH &&
                    id.getTagHash() == OLD_IOS_HASH) {

                    maapi.setElem(th,
                                  newid,
                                  "/devices/device{%s}/device-type/cli/ned-id",
                                  nameBuf.toString());
                }

            }
            s1.close();
            s2.close();
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

}
