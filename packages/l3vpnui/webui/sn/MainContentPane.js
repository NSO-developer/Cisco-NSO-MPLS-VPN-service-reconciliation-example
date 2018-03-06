define([
    'dojo/_base/declare',

    'dijit/layout/ContentPane',
    'dijit/MenuBar',
    'dijit/Menu',
    'dijit/MenuItem',
    'dijit/MenuSeparator',
    'dijit/PopupMenuBarItem',

    'xwt/widget/layout/XwtTabContainer',

    'tailf/core/logger',
    'tailf/core/protocol/JsonRpcHelper',
    'tailf/dijit/form/Select',

    'tailf/xwt/Utils',
    'tailf/xwt/widget/dialogs/ModalSchemaDialog',

    'ncs/widget/actions/DeviceBatchActions',

    './Services',
    './QoS',
    './Topology',
    './dialogs'
], function(
    declare,

    ContentPane,
    MenuBar, Menu, MenuItem, MenuSeparator, PopupMenuBarItem,

    TabContainer,

    logger,
    JsonRpcHelper,

    Select,

    xwtUtils, ModalSchemaDialog,
    DeviceBatchActions,

    Services,
    QoS,
    Topology,

    dialogs
) {

return declare([ContentPane], {
    class : 'l3vpnui-main-pane dijitContentBase',

    constructor : function() {
        var me = this;

        this.servicesWidget = undefined;
        this.topologyWidget = undefined;
        this.qosWidget = undefined;

        me._thWriteListener = JsonRpcHelper.addListener('global-write-th', function(args) {
            if ((args.action === 'removed') && (args.specific === 'reverted')) {
                me.servicesWidget.redraw();
                me.servicesWidget.refreshServiceTable();
                me.servicesWidget.refreshDeviceList();
                me.topologyWidget.refreshList();
            }
        });

        this.actions = new DeviceBatchActions();
    },

    destroy : function() {
        JsonRpcHelper.deleteListener(this._thWriteListener);

        // FIXME: For some reason the servicesWidget isn't cleaned up
        if (this.servicesWidget) {
            this.servicesWidget._cleanup();
        }

        this.inherited(arguments);
    },

    postCreate: function() {
        var me = this;
        me.inherited(arguments);

        var menu = me._getMenuBar();
        var tabs = me._getTabs();

        me.addChild(menu);
        me.addChild(tabs);
    },

    _getMenuBar : function() {
        var me = this;

        var mb = new MenuBar({
            region : 'top'
        });

        var serviceMenu = new Menu();
        var topologyMenu = new Menu();
        var devicesMenu = new Menu();

        // --- Services Menu
        serviceMenu.addChild(new MenuItem({
            label : 'Add VPN...',
            onClick : function() {
                me._addServiceDialog();
            }
        }));

        // --- Topology menu
        topologyMenu.addChild(new MenuItem({
            label : 'Add Connection...',
            onClick : function() {
                me._addTopologyConnectionDialog();
            }
        }));

        // --- Devices Menu
        devicesMenu.addChild(new MenuItem({
            //iconClass : 'dijitIconCut',
            label     : 'Sync-From',
            onClick   : function() {
                me._devicesSyncFrom();
            }
        }));

        mb.addChild(new PopupMenuBarItem({
            label : '&nbsp;L3 VPN',
            popup : serviceMenu
        }));

        mb.addChild(new PopupMenuBarItem({
            label : '&nbsp;Topology',
            popup : topologyMenu
        }));

        mb.addChild(new PopupMenuBarItem({
            label : '&nbsp;Devices',
            popup : devicesMenu
        }));

        return mb;
    },

    _getTabs : function() {
        var me = this;

        this.servicesWidget = new Services({
            callbacks : {
                topologyUpdated : function() {
                    me.topologyWidget.refreshList();
                }
            }
        });

        this.topologyWidget = new Topology();
        this.qosWidget = new QoS();

        var tabContainer = new TabContainer({
            style: 'height: 100%;'
        });

        tabContainer.addChild(this.servicesWidget);
        tabContainer.addChild(this.topologyWidget);
        tabContainer.addChild(this.qosWidget);

        return tabContainer;
    },

    _devicesSyncFrom : function() {
        var me = this;

        var deviceNames = me.servicesWidget.getAllDevices();
        var count = 0;

        function _resultCallback(result) {
            count += 1;
            if (count === deviceNames.length) {
                me.servicesWidget.redraw();
            }
        }

        this.actions.syncFrom(deviceNames, _resultCallback);
    },

    _addServiceDialog : function() {
        var me = this;
        var dlg = new ModalSchemaDialog({
            title : 'Add L3 VPN',

            additionalClass : 'l3vpnui-topology-service-dialog',

            type    : 'list',
            action  : 'create',

            keypath : '/l3vpn:vpn/l3vpn',

            cols    : 1,

            fields  : [{
                leaf : 'name',
                text : 'Name',
                type : {
                    isKey : true
                }
            }, {
                leaf : 'route-distinguisher',
                text : 'Route Distinguisher'
            }],

            callbacks : {
                onValidOk : function() {
                    me.servicesWidget.refreshServiceTable();
                }
            }
        });

        dlg.startup();
        dlg.show();
    },

    _addTopologyConnectionDialog : function() {
        var me = this;

        dialogs.addTopologyConnectionDialog({
            callbacks : {
                onValidOk : function() {
                    me.topologyWidget.refreshList();
                }
            }
        });
    }

});
});
