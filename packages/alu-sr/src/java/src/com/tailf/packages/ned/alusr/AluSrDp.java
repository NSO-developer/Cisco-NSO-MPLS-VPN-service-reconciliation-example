package com.tailf.packages.ned.alusr;

import java.io.IOException;
import java.net.Socket;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBool;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;
import com.tailf.dp.annotations.DataCallback;
import com.tailf.dp.annotations.TransCallback;
import com.tailf.dp.proto.DataCBType;
import com.tailf.dp.proto.TransCBType;
import com.tailf.maapi.Maapi;
import com.tailf.ncs.NcsMain;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;

/**
 * Implements various set hooks needed for
 * initializing additional config on new
 * instances.
 *
 * @author jrendel
 *
 */
public class AluSrDp {

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;

    /**
     * Simple utility for creating new instances.
     * @param tid
     * @param cp
     * @throws IOException
     * @throws ConfException
     */
    private void
    createInstance(int tid, ConfPath cp)
        throws IOException, ConfException {
        if (mm.exists(tid, cp) == false) {
            try {
                mm.sharedCreate(tid, cp);
            }
            catch (Exception e) {
                mm.safeCreate(tid, cp);
            }
        }
    }

    /**
     * Simple utility for removing new instances.
     * @param tid
     * @param cp
     * @throws IOException
     * @throws ConfException
     */
    private void
    removeInstance(int tid, ConfPath cp)
        throws IOException, ConfException {
        if (mm.exists(tid, cp) == true) {
            try {
                mm.delete(tid, cp);
            }
            catch (Exception e) {
                mm.safeDelete(tid, cp.toString());
            }
        }
    }


    /**
     * When the router * / mpls is created the ALU device by default
     * creates router * / interface / "system". It also creates the
     * router * / rsvp and router * / interface "system"
     * This callback mimics this behavior.
     *
     * @param trans    - Transaction handle
     * @param keyPath  - Key path to router * / mpls instance
     *
     * @return REPLY_OK;
     * @throws DpCallbackException
     */
    @DataCallback(callPoint="mpls-hook", callType=DataCBType.CREATE)
    public int mplsCreate(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            int tid = trans.getTransaction();
            ConfPath cp = new ConfPath(keyPath);

            createInstance(tid, cp.copyAppend("/interface{system}"));
            createInstance(tid, cp.append("/../rsvp"));
            createInstance(tid, cp.append("/interface{system}"));

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    /**
     * When the / router * / mpls is deleted the ALU device by default
     * also creates the / router * / rsvp
     * This callback mimics this behavior.
     *
     * @param trans    - Transaction handle
     * @param keyPath  - Key path to /router * / mpls
     *
     * @return REPLY_OK;
     * @throws DpCallbackException
     */
    @DataCallback(callPoint="mpls-hook", callType=DataCBType.REMOVE)
    public int mplsRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            int tid = trans.getTransaction();
            ConfPath cp = new ConfPath(keyPath);

            removeInstance(tid, cp.append("/../rsvp"));

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    /**
     * When a / qos / network-queue instance is created on the device
     * it also by default creates two instances of
     * / qos / network-queue / queue with ids 1 and 9.
     * This callback mimics this behavior.
     *
     * @param trans    - Transaction handle
     * @param keyPath  - Key path to network-queue instance
     *
     * @return REPLY_OK;
     * @throws DpCallbackException
     */
    @DataCallback(callPoint="network-queue-hook", callType=DataCBType.CREATE)
    public int networkQueueCreate(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            int tid = trans.getTransaction();
            ConfPath cp = new ConfPath(keyPath);

            createInstance(tid, cp.copyAppend("/queue{1}"));
            createInstance(tid, cp.copyAppend("/queue{9}"));
            createInstance(tid, cp.copyAppend("/queue{9}/multipoint"));

            return Conf.REPLY_OK;
        }
        catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }


    /**
     * Constructor
     *
     * @param trans - transaction handle
     * @throws DpCallbackException
     */
    @TransCallback(callType=TransCBType.INIT)
    public void AluSrDpInit(DpTrans trans) throws DpCallbackException {

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


    /**
     * Destructor
     *
     * @param trans - transaction handle
     * @throws DpCallbackException
     */
    @TransCallback(callType=TransCBType.FINISH)
    public void AluSrDpFinish(DpTrans trans) throws DpCallbackException {

        try {
            mm.detach(trans.getTransaction());
        }
        catch (Exception e) {
            ;
        }
    }
}
