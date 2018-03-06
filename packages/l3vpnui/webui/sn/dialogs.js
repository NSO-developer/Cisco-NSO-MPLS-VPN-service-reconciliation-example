define([
    'tailf/core/logger',
    'tailf/core/protocol/JsonRpc',
    'tailf/core/protocol/JsonRpcHelper',
    'tailf/dijit/form/Select',
    'tailf/xwt/widget/dialogs/ModalSchemaDialog',
    'ncs/model/device/demo-devices'
], function(
    logger,
    JsonRpc, JsonRpcHelper,
    Select,
    ModalSchemaDialog,
    demoDevices
) {

function _topologyConnectionDialog(args) {
    var me = this;

    function _updateInterfaceWidget(deviceName, widget) {
        JsonRpcHelper.read().done(function(th) {
            demoDevices.getType(th, deviceName).done(function(type) {
                demoDevices.getInterfaces(th, deviceName, type).done(function(ifcs) {
                    var options = [];
                    _.each(ifcs, function(ifc) {
                        options.push(ifc.name);
                    });

                    widget.setOptions(options);
                }).fail(function(err) {
                    logger.error(' ERR=', err);
                });
            });
        });
    }

    var ep1InterfaceWidget;
    var ep2InterfaceWidget;

    var ep1DeviceLoaded = $.Deferred();
    var ep2DeviceLoaded = $.Deferred();

    var initial = {
        connection : {
            name : undefined,
            vlan : undefined
        },

        ep1 : {
            device  : undefined,
            address : undefined
        },
        ep2 : {
            device  : undefined,
            address : undefined
        }
    };

    if (args) {
        initial = _.assign(initial, args.initial);
    }

    ep1DeviceLoaded.done(function(widget) {
        _updateInterfaceWidget(widget.getValue(), ep1InterfaceWidget);
    });

    ep2DeviceLoaded.done(function(widget) {
        _updateInterfaceWidget(widget.getValue(), ep2InterfaceWidget);
    });

    if (args && args.initial) {
        var ini = args.initial;
        initialEp1Device = ini.ep1.device;
    }

    var dlg = new ModalSchemaDialog({
        title : args.title,

        additionalClass : 'l3vpnui-topology-connection-dialog',

        type    : 'list',
        action  : 'create',

        keypath : '/l3vpn:topology/connection',

        cols    : 2,

        callbacks : {
            preExecute : args.callbacks ? args.callbacks.preExecute : undefined,
            onValidOk  : args.callbacks ? args.callbacks.onValidOk  : undefined
        },

        fields  : [{
            leaf : 'name',
            text : 'Name',
            initialValue : initial.connection.name,
            type : {
                isKey : true
            }
        }, {
            leaf : 'link-vlan',
            text : 'Link VLAN',
            initialValue : initial.connection.vlan
        }, {
            type   : 'fieldset',
/*
            fieldset : {
                extraClass : 'the extra class',
                hidden     : true
            },
*/
            legend : 'Endpoint 1',

            fields : [{
                leaf : 'endpoint-1/device',
                text : 'Device',
                initialValue : initial.ep1.device,
                type : {
                    kind : 'leafref-list',
                    path : '/ncs:devices/device',
                    onChange : function(deviceName) {
                        _updateInterfaceWidget(deviceName, ep1InterfaceWidget);
                    },

                    loadDeferred : ep1DeviceLoaded
                }

            }, {
                leaf : 'endpoint-1/interface',
                text : 'Interface',
                createWidget : function(field) {
                    ep1InterfaceWidget = new Select({
                        ownTitle : false,
                        label    : field.text
                    });
                    return ep1InterfaceWidget;
                }
            }, {
                leaf : 'endpoint-1/ip-address',
                text : 'Ip Address',
                initialValue : initial.ep1.address
            }]
        }, {
            type   : 'fieldset',
            legend : 'Endpoint 2',

            fields : [{
                leaf : 'endpoint-2/device',
                text : 'Device',
                initialValue : initial.ep2.device,
                type : {
                    kind : 'leafref-list',
                    path : '/ncs:devices/device',
                    onChange : function(deviceName) {
                        _updateInterfaceWidget(deviceName, ep2InterfaceWidget);
                    },

                    loadDeferred : ep2DeviceLoaded
                 }
             }, {
                leaf : 'endpoint-2/interface',
                text : 'Interface',
                createWidget : function(field) {
                    ep2InterfaceWidget = new Select({
                        ownTitle : false,
                        label    : field.text
                    });
                    return ep2InterfaceWidget;
                }
             }, {
                leaf : 'endpoint-2/ip-address',
                text : 'Ip Address',
                initialValue : initial.ep2.address
            }]
         }]
    });

    dlg.startup();
    dlg.show();
}

function m_addTopologyConnectionDialog(args) {
    _topologyConnectionDialog({
        title     : 'Add Connection',
        callbacks : args.callbacks
    });
}

function m_moveTopologyConnectionDialog(args) {
    var connection = _.assign({
        ep1 : {},
        ep2 : {}
    }, args.connection);

    var deleteConnectionName = connection.name;

    function _deleteOriginalConnection(th) {
        var path = '/l3vpn:topology/connection{"' + deleteConnectionName + '"}';
        return JsonRpc('delete', {
            th   : th,
            path : path
        });
    }

    _topologyConnectionDialog({
        title    : 'Move Connection',
        callbacks : {
            preExecute : _deleteOriginalConnection,
            onValidOk  : args.callbacks.onValidOk
        },

        initial  : {
            connection : {
                name : connection.name,
                vlan : connection.vlan
            },

            ep1 : {
                device  : args.ceDevice,
                address : connection.ep1.address
            },
            ep2 : {
                device : args.peDevice,
                address : connection.ep2.address
            }
        }

    });

}

return {
    addTopologyConnectionDialog  : m_addTopologyConnectionDialog,
    moveTopologyConnectionDialog : m_moveTopologyConnectionDialog
}

});
