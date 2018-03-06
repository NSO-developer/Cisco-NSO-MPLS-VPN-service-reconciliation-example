#!/usr/bin/env python
import _ncs
import _ncs.deprecated.maapi as maapi

V = _ncs.Value

def set_icon(trans, name, filename) :
    path = '/ncs:webui/icons/icon{"' + name + '"}'

    trans.create_allow_exist(path)

    d = V(open(filename).read(), _ncs.C_BINARY)
    trans.set_elem(d, path + '/image')


def set_device_state_icon(trans, deviceName, state, iconName) :
    path = '/ncs:webui/icons/device{"' + deviceName + '" ' + state + ' large}'

    trans.create_allow_exist(path)
    trans.set_elem(iconName, path + '/icon')


def set_device_icon(trans, deviceName, icon) :
    set_device_state_icon(trans, deviceName, 'disabled', icon + '-disabled')
    set_device_state_icon(trans, deviceName, 'enabled', icon + '-enabled')


def write_icons() :

    with maapi.wctx.connect(ip = '127.0.0.1', port = _ncs.NCS_PORT) as c :
        with maapi.wctx.session(c, 'admin') as s :
            with maapi.wctx.trans(s, readWrite = _ncs.READ_WRITE) as t :

                imgPath = '../packages/l3vpnui/webui/images'

                set_icon(t, 'cisco-enabled', imgPath + '/cisco-enabled.png')
                set_icon(t, 'cisco-disabled', imgPath + '/cisco-disabled.png')
                set_icon(t, 'juniper-enabled', imgPath + '/juniper-enabled.png')
                set_icon(t, 'juniper-disabled', imgPath + '/juniper-disabled.png')

                set_device_icon(t, 'ce0', 'cisco')
                set_device_icon(t, 'ce1', 'cisco')
                set_device_icon(t, 'ce2', 'cisco')
                set_device_icon(t, 'ce3', 'cisco')
                set_device_icon(t, 'ce4', 'cisco')
                set_device_icon(t, 'ce5', 'cisco')
                set_device_icon(t, 'ce6', 'cisco')
                set_device_icon(t, 'ce7', 'cisco')
                set_device_icon(t, 'ce8', 'cisco')

                set_device_icon(t, 'p0', 'cisco')
                set_device_icon(t, 'p1', 'cisco')
                set_device_icon(t, 'p2', 'cisco')
                set_device_icon(t, 'p3', 'cisco')

                set_device_icon(t, 'pe0', 'cisco')
                set_device_icon(t, 'pe1', 'cisco')
                set_device_icon(t, 'pe2', 'juniper')
                set_device_icon(t, 'pe3', 'cisco')

                t.apply()

if __name__ == '__main__' :
    write_icons()
