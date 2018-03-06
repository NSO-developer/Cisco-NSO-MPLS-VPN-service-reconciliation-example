define([
    'lodash',
    'dojo/_base/declare',
    'dojo/_base/array',
    'dojo/_base/connect',
    'dojo/_base/lang',
    'dojo/_base/window',
    'dojo/aspect',
    'dojo/dom',
    'dojo/dom-attr',
    'dojo/dom-class',
    'dojo/dom-construct',
    'dojo/dom-geometry',
    'dojo/dom-style',
    'dojo/mouse',
    'dojo/on',
    'dojo/Deferred',
    'dojo/promise/all',
    'dojo/query',

    'dojo/dnd/Source',
    'dojo/dnd/Target',

    'dijit/registry',
    'dijit/layout/BorderContainer',
    'dijit/layout/ContentPane',
    'dijit/form/TextBox',
    'dijit/form/Select',

    'dojox/gfx',
    'dojox/gfx/matrix',

    'dojox/layout/TableContainer',

    'xwt/widget/form/TextButton',
    'xwt/widget/layout/Dialog',
    'xwt/widget/layout/XwtTabContainer',

    'tailf/global',
    'tailf/core/logger',
    'tailf/dijit/html',
    'tailf/dijit/menu',
    'tailf/dijit/schema/List',
    'tailf/dijit/form/Select',
    'tailf/core/protocol/JsonRpc',
    'tailf/core/protocol/JsonRpcErr',
    'tailf/core/protocol/JsonRpcHelper',

    'tailf/xwt/widget/dialogs/ModalSchemaDialog',
    'tailf/dijit/dialogs/InlineSchemaDialog',

    'ncs/path',
    'ncs/Href',

    'ncs/model/device/DeviceHelper',
    'ncs/model/device/demo-devices',

    'ncs/services/service-actions',
    'ncs/widget/service/action-display',

    './form/QosSelect',
    './dialogs',
    './service-config',
    'ncs/examples/svg',
    'ncs/examples/dojox/device-map',
    'ncs/examples/services/actions',
    'ncs/examples/services/actions-menu'
], function(
    _,
    declare, array, connect, lang, win, aspect,
    dom, domAttr, domClass, domConstruct, domGeometry, domStyle,
    mouse, on, Deferred, all, query,

    Source,
    Target,

    registry,
    BorderContainer,
    ContentPane,
    TextBox,
    DijitSelect,

    gfx,
    matrix,

    TableContainer,
    TextButton,
    Dialog,
    TabContainer,

    tailfGlobal,
    logger,
    dijitHtml,
    dijitMenu,
    List,
    Select,
    JsonRpc, JsonRpcErr, JsonRpcHelper,

    ModalSchemaDialog,
    InlineSchemaDialog,
    ncsPath,
    ncsHref,
    DeviceHelper,

    demoDevices,
    serviceActions,
    actionDisplay,
    QosSelect,
    dialogs, serviceConfig, exSvg, deviceMap, exServiceActions, exActionsMenu
) {

function _trace() {
    //logger.tracePrefix('l3vpnui/Services : ', arguments);
}


return declare([BorderContainer], {
    title: 'L3 VPN',
    iconClass: 'icon-desktop',
    widgetsInTemplate: true,
    //templateString: template,

    callbacks : {
        topologyUpdated : undefined
    },

    constructor: function() {
        this._mouse = {x: 0, y: 0};
        gfx.useSvgWeb = true;
        this.devices = {};
        this._currentServiceRowId = undefined;
        this._detailWidgets = [];

        this.cliOutput = undefined;
        this.nativeOutput = undefined;

        this._cleanupCalled = false;

        this.dndDropSubscription = undefined;
        this.gfxMoveStopSubscription = undefined;
        this.gfxMoveStartSubscription = undefined;
    },

    destroy : function() {
        this._cleanup();


        _.each(this._detailWidgets, function(w) {
            w.destroy();
        });
        this._unsubscribe();
        this.inherited(arguments);
    },

    _unsubscribe: function() {
        if(this.dndDropSubscription) {
            connect.unsubscribe(this.dndDropSubscription);
        }
        if(this.gfxMoveStopSubscription) {
            connect.unsubscribe(this.gfxMoveStopSubscription);
        }
        if(this.gfxMoveStartSubscription) {
            connect.unsubscribe(this.gfxMoveStartSubscription);
        }
    },

    // FIXME : Fix since destroy isn't called for this widget, investigate
    _cleanup : function() {
        if (this._cleanupCalled) {
            return;
        }
        this._cleanupCalled = true;

        actionDisplay.destroyCliResultOutput(this.cliOutput);
        actionDisplay.destroyNativeResultOutput(this.nativeOutput);
     },

    onShow: function() {
        var me = this;
        me.redraw();
        this.inherited(arguments);
    },


    postCreate: function() {
        var me = this;

        me.actionCtx = new exServiceActions.Context({
            cliResultClass    : 'l3vpn-cli-diff-pane',
            nativeResultClass : 'l3vpn-native-diff-pane'
        });

        this._createSvgPane().then(function(pane) {
            me._svgPane = pane;
            me.addChild(pane);
        });
        var devicePane = me._createDevicePane(),
            servicePane = me._createServicePane();

        me.addChild(devicePane);
        me.addChild(servicePane);

        this.setupConnections();
        this.inherited(arguments);
    },

    startup : function() {
        var me = this;
        this.inherited(arguments);
   },

    getDevicesOnMap : function() {
        var ret = [];
        _.each(this.devices, function(d) {
            if ('svg' in d) {
                ret.push(d.name);
            }
        });

        return ret;
    },

    getAllDevices : function() {
        var ret = [];
        _.each(this.devices, function(d) {
            ret.push(d.name);
        });

        return ret;
    },

    redraw : function() {
        var me = this;

        JsonRpcHelper.read().done(function(th) {
            me._loadDevices(th).then(function(devices) {
                me.devices = devices;
                return me._loadDevicePositions(th, me.devices);
            }).then(function() {
                return me._loadConnections(th);
            }).then(function(connections) {
                me._connections = connections;
                return me._renderDevices(th);
            }).then(function() {
                me._renderPaths();
                me.refreshDeviceList();
            });
        });
    },

    refreshServiceTable : function() {
        var me = this;
        me._serviceList.refresh();
        _.each(me._detailWidgets, function(w) {
            w.refresh();
        });
    },

    refreshDeviceList : function() {
        var me = this;
        var rows = query('.gridxRow', me._deviceList.domNode);

        _.each(rows, function(domRow) {
            var rowid = domRow.attributes.getNamedItem('rowid').value,
                row = me._deviceList.grid.row(rowid);

            var gs = query('#' + row.item().name, me._surface);

            if(gs.length > 0) {
                domClass.add(domRow, 'dojoDndDisabled');
            } else {
                domClass.remove(domRow, 'dojoDndDisabled');
                domClass.add(domRow, 'dojoDndItem');
            }
        });
    },

    setupConnections: function() {
        var me = this;

        me.dndDropSubscription = connect.subscribe('/dnd/drop', function(source, nodes, isCopy, target) {
            var name = nodes[0].textContent,
                dev = me.devices[name];

            if(!('svg' in dev) && me._inSvgContainer) {

                var divx = domGeometry.position(me.surface.rawNode).x,
                    divy = domGeometry.position(me.surface.rawNode).y,
                    x = me._mouse.x - divx,
                    y = me._mouse.y - divy,
                    relx = deviceMap.getRelativeX(me.mapCtx, me._mouse.x),
                    rely = deviceMap.getRelativeY(me.mapCtx, me._mouse.y);

                JsonRpcHelper.read().done(function(th) {
                    dev.svg = me._renderDevice(th, x, y, dev.name, dev.state);
                });

                deviceMap.setDeviceCoord({
                    context    : me.mapCtx,
                    deviceName : dev.name,
                    relativeX  : relx,
                    relativeY  : rely
                });

                me.refreshDeviceList();
            }
        });

        me.gfxMoveStartSubscription = connect.subscribe('/gfx/move/start', function(mover) {
            me._mover = mover;
            me._movingDevice = me.devices[mover.host.name];

            mover.startX = mover.shape.children[0].shape.cx;
            mover.startY = mover.shape.children[0].shape.cy;
            var url = '/resources/running/__device_icon/' + mover.host.name + '/state/disabled/size/large?1';
            domStyle.set(me.containerNode, 'cursor', 'url("' + url + '"), move');
        });

        me.gfxMoveStopSubscription = connect.subscribe('/gfx/move/stop', function(mover) {
            domStyle.set(me.containerNode, 'cursor', '');
            var device = me._mover.host.name;

            if (me._currentServiceRowId) {
                var row = me._serviceList.grid.row(me._currentServiceRowId);
                var serviceName = row.item().name;
                me._addEndpointDialog = me._createAddEndpointDialog(serviceName, device, row);
                me._addEndpointDialog.show();

                if(!row.isDetailShown()) {
                    row.refreshDetail();
                    row.showDetail();
                }
            }

            if(!me._inSvgContainer) {
                deviceMap.resetDevicePosition({
                    context : me.mapCtx,
                    mover   : mover
                });
            } else {
                var xx = me._surfaceX(mover.lastX);
                var yy = me._surfaceY(mover.lastY);
                var closeToDevice = me._getCloseDevice(xx, yy, me._movingDevice.name);

                if (closeToDevice && me._isCeDevice(me._movingDevice) && me._isPeDevice(closeToDevice)) {
                    // CE-device over PE-device, move topology
                    deviceMap.resetDevicePosition({
                        context : me.mapCtx,
                        mover   : mover
                    });

                    me._moveCePeTopologyDialog({
                        ceDeviceName     : me._movingDevice.name,
                        toPeDeviceName   : closeToDevice.name
                    });
                } else {
                    var x = deviceMap.getRelativeX(me.mapCtx, mover.lastX);
                    var y = deviceMap.getRelativeY(me.mapCtx, mover.lastY);

                    deviceMap.setDeviceCoord({
                        context    : me.mapCtx,
                        deviceName : device,
                        relativeX  : x,
                        relativeY  : y
                    });
                }
            }

            me._mover = undefined;
            me._movingDevice = undefined;
        });
    },

    _highlightDevices: function(devices) {
        var me = this;
        var fill = [0, 0x88, 0xcc, 0.2];
        array.forEach(devices, function(dev) {
            if(me.devices.hasOwnProperty(dev) && 'svg' in me.devices[dev]) {
                me.devices[dev].svg.shape.children[0].setFill(fill);
            }
        });
    },

    _clearHighlightDevices: function() {
        var me = this;
        for(var dev in me.devices) {
            if('svg' in me.devices[dev]) {
                me.devices[dev].svg.shape.children[0].setFill(null);
            }
        }
    },

    _createDevicePane: function() {
        var me = this;
        var pane = new ContentPane({
            region   : 'left',
            splitter : true,
            minSize  : 0,
        });

        var list = new List({
            keypath : '/ncs:devices/device',
            keys    : ['name'],
            fields : [{
                name : 'name',
                text : 'Device'
            }]

        });

        me._deviceList = list;

        var source = new Source(list.domNode, {
            withHandles: false,
            allowNested: true,
            copyOnly: true,
            singular: true,
            autoSync: true
        });

        pane.addChild(list);
        pane.startup();

        domGeometry.setMarginBox(pane.domNode, {w:0});
        return pane;
    },

    _createServicePane: function() {
        var me = this;

        function _nameDropDownButton(el) {
            var __serviceInfo = {
                name     : '',
                itemPath : ''
            };

            function _servicePath() {
                return button.__serviceInfo.itemPath;
            }

            function _serviceName() {
                return _servicePath()._serviceName;
            }

            var eam = new exActionsMenu.DefaultActions({
                actionContext : me.actionCtx,
                servicePath   : _servicePath,
                serviceName   : _serviceName
            });

            var button = dijitMenu.dropDownButton({
                button : {
                    label : '',
                    extraClass : 'l3vpn-service-name-button-menu',
                    onClick : function() {
                        var href = ncsHref.getModelHref(_servicePath());
                        ncsHref.navigateToHref(href);
                    }
                },
                menu : [
                    eam.getCheckSync('boolean'),
                    eam.getCheckSync('cli'),
                    eam.getDeepCheckSync('cli'),
                    eam.getRedeploy(),
                    eam.getRedeployDryRun('native'),
                    eam.getRedeployDryRun('cli'),

                    'separator',

                    {
                        label : 'Delete...',
                        onClick : function() {
                            me._deleteService(button.__serviceInfo.itemPath, list);
                        }
                    }]
            });

            button.__serviceInfo = __serviceInfo;
            button.placeAt(el);
        }


        var pane = new ContentPane({
            region   : 'center',
            splitter : true,
            id: 'serviceContainer'
        });

        var list = new List({
            id: 'l3vpn-vpn-list',
            keypath : '/l3vpn:vpn/l3vpn',
            keys    : ['name'],
            editable : true,

            fields : [{
                name : 'name',
                text : 'Name',
                widgetsInCell : true,
                onCellWidgetCreated : function(cellWidget) {
                    _nameDropDownButton(cellWidget.domNode);
                },

                setCellValue : function(gridData, storeData, cellWidget) {
                    var el = $(cellWidget.domNode).find('.l3vpn-service-name-button-menu')[0];
                    var button = registry.byNode(el);

                    var serviceName = gridData;
                    /*jshint -W053 */
                    var itemPath = new String(ncsPath.path('/l3vpn:vpn/l3vpn', serviceName));
                    itemPath._serviceName = serviceName;

                    button.setLabel(serviceName);
                    button.__serviceInfo.name = serviceName;
                    button.__serviceInfo.itemPath = itemPath;
                }
            }, {
                name : 'route-distinguisher',
                text : 'Route Distinguisher',
                editable : true,
            }, {
                name : 'qos/qos-policy',
                text : 'QoS',
                editable: true,

                yang : {
                    kind             : 'leaf',
                    type             : 'leafref-list',
                    leafrefPath      : '/l3vpn:qos/qos-policy',
                    allowEmptyString : true
                }
            }],

            callbacks : {
                renderDetail : function(args) {
                    me._createServiceDetails(args);
                },

                rowClick : function(args) {
                    me._onServiceRowClick(args.rowData);
                },

                rowCreated : function(args) {
                    me._onServiceRowCreated(args.row, args.element, args.rowData);
                }
            }
        });

        me._serviceList = list;
        pane.addChild(list);

        list.resize();

        return pane;
    },

    _onServiceRowClick : function(rowData) {
        var me = this;
        JsonRpcHelper.read().done(function(th) {
            var path = serviceConfig.keypath(rowData.name) + '/device-list'
            JsonRpc('get_value', {
                th   : th,
                path : path
            }).done(function(result) {
                me._clearHighlightDevices();
                me._highlightDevices(result.value);
            })
        });
    },

    _onServiceRowCreated : function(row, rowElement, rowData) {
        var me = this;
        var service = rowData.name;

        on(rowElement, mouse.enter, function(e) {
            if(me._mover) {
                row.showDetail();
            }
            _trace('mouse.enter', service);
            me._currentServiceRowId = row.id;
        });

        on(rowElement, mouse.leave, function(e) {
            if(me._mover) {
                setTimeout(function() {
                    row.hideDetail();
                }, 10);
            }
            _trace('mouse.leave', service);
            me._currentServiceRowId = undefined;
        });
    },

    _createServiceDetails : function(args) {
        var me = this;
        var keypath = args.rowKeypath + '/endpoint';
        var service = args.row.name;

        function _idDropDownButton(el) {
            var __epInfo = {
                itemPath : ''
            };

            var button = dijitMenu.dropDownButton({
                button : {
                    label : '', // Set in setCellValue
                    extraClass : 'l3vpn-ce-id-button-menu',
                    onClick : function() {
                        var href = ncsHref.getModelHref(button.__epInfo.itemPath);
                        ncsHref.navigateToHref(href);
                    }
                },
                menu : [{
                    label : 'Delete...',
                    onClick : function() {
                        me._deleteEndpoint(button.__epInfo.itemPath, details);
                    }
                }]
            });

            button.__epInfo = __epInfo;
            button.placeAt(el);
        }

        function _updateInterfaceWidget(deviceName, widget, interface) {
            JsonRpcHelper.read().done(function(th) {
                demoDevices.getType(th, deviceName).done(function(type) {
                    demoDevices.getInterfaces(th, deviceName, type).done(function(ifcs) {
                        var options = [];
                        _.each(ifcs, function(ifc) {
                            options.push({label: ifc.name, value: ifc.name});
                        });

                        widget.addOption(options);
                        widget.attr('value', interface);

                    }).fail(function(err) {
                        logger.error(' ERR=', err);
                    });
                });

            });
        }

        var details = new List({
            id: 'l3vpnui-service-inline-edit-list-' + service,
            additionalClass : 'l3vpnui-service-inline-edit-list',
            style : 'height : 150px;',
            keypath : keypath,
            keys: ['id'],

            fields: [{
                name : 'id',
                text : 'Id',
                widgetsInCell : true,
                onCellWidgetCreated : function(cellWidget) {
                    _idDropDownButton(cellWidget.domNode);
                },
                setCellValue : function(gridData, storeData, cellWidget) {
                    var epId = gridData;
                    var itemPath = ncsPath.path(keypath, epId);
                    var button = registry.byNode($(cellWidget.domNode).find('.l3vpn-ce-id-button-menu')[0]);

                    button.setLabel(epId);
                    button.__epInfo.itemPath = itemPath;
                }
             }, {
                name : 'ce-device',
                text : 'CE Device',
                decorator : function(value) {
                    return dijitHtml.a(
                        ncsHref.getModelHref(ncsPath.device(value)),
                        value);
                 }
            }, {
                name : 'ce-interface',
                text : 'CE Interface',
                widgetsInCell : true,
                onCellWidgetCreated : function(cellWidget) {
                    var select = new DijitSelect({
                        class : 'l3vpn-ce-interface-select',
                        options: []
                    });
                    select.onChange = function(ifc) {
                        var path = this.keypath;
                        if (ifc !== this.currentInterface) {
                            JsonRpcHelper.write().done(function(th) {
                                JsonRpc('set_value', {th: th, path: path, value: ifc}).done();
                            });
                        }
                    };
                    select.placeAt(cellWidget.domNode);

                },
                setCellValue : function(gridData, storeData, cellWidget) {
                    var dev = cellWidget.cell.row.item()['ce-device'];
                    var endpoint = cellWidget.cell.row.item().id;

                    var widget = registry.byNode($(cellWidget.domNode).find('.l3vpn-ce-interface-select')[0]);
                    widget.keypath = '/l3vpn:vpn/l3vpn{' + service + '}/endpoint{' + endpoint + '}/ce-interface';
                    widget.currentInterface = gridData;
                    _updateInterfaceWidget(dev, widget, gridData);
                }
            }, {
                name : 'ip-network',
                text : 'IP Network',
                editable : true
            }, {
                name : 'as-number',
                text : 'AS Number',
                editable : true
            }, {
                name : 'bandwidth',
                text : 'Bandwidth',
                editable : true
            }],

            callbacks : {
                rowClick : function(args) {
                    me._onServiceDetailRowClick(args.rowData);
                }
            }
        });

        me._detailWidgets.push(details);

        details.placeAt(args.node);
        details.startup();
        args.deferred.callback();
    },

    _onServiceDetailRowClick : function(rowData) {
        var device = rowData['ce-device'];
        this._clearHighlightDevices();
        this._highlightDevices([device]);
    },

    _createSvgPane: function() {
        var def = new Deferred();
        var me = this;
        var pane = new ContentPane({
            region: 'top',
            splitter : true,

            /* FIXME : Resize messes with positioning, needs more investigation
            resize : function(size) {
                var ch1 = $(pane.containerNode).find('div')[0];

                if (size.w !== undefined) {
                    $(ch1).css('width', (size.w-5) + 'px');
                }

                if (size.h !== undefined) {
                    $(ch1).css('height', (size.h-5) + 'px');
                    me.surface.setDimensions($(ch1).width(), $(ch1).height());
                }
            }
            */
        });

        var surfaceDiv = domConstruct.create('div', {style: 'display: inline-block;'}, pane.containerNode);

        var target = new Target(surfaceDiv);
        this._renderSurface(surfaceDiv).then(function() {
            def.resolve(pane);
        });

        return def;
    },

    _deletePath : function(path, update) {
        JsonRpcHelper.write().done(function(th) {
            JsonRpc('delete', {
                th : th,
                path : path
            }).done(function() {
                update();
            }).fail(function(err) {
            });
        });
    },

    _deleteService : function(path, list) {
        this._deletePath(path, function() {
            list.refresh();
        });
    },

    _deleteEndpoint : function(path, list) {
        this._deletePath(path, function() {
            list.refresh();
        });
   },

    _loadDevices: function(th) {
        var me = this;
        var def = new Deferred();

        JsonRpcHelper.query({
            th: th,
            context_node: '/ncs:devices',
            xpath_expr: 'device',
            selection: ['name', 'address', 'port']
        }).done(function(res) {
            var devices = {};

            array.forEach(res.results, function(item) {
                var name = item[0];

                devices[name] = {
                    name: name,
                    address: item[1],
                    port: item[2],
                    state : undefined
                 };
            });

            def.resolve(devices);
        });

        return def;
    },

    _loadConnections: function(th) {
        var me = this;
        var def = new Deferred();

        JsonRpcHelper.query({
            th: th,
            context_node: '/l3vpn:topology',
            xpath_expr: 'connection',
            selection: [
                'name', 'link-vlan',
                'endpoint-1/device', 'endpoint-1/ip-address',
                'endpoint-2/device', 'endpoint-2/ip-address']
        }).done(function(result) {
            var connections = [];

            _.each(result.results, function(item) {
                connections.push({
                    name : item[0],
                    vlan : item[1],
                    ep1  : {
                        device  : item[2],
                        address : item[3]
                    },
                    ep2  : {
                        device  : item[4],
                        address : item[5]
                    }
                });
            });

            def.resolve(connections);
        });
        return def;
    },

    _loadDevicePositions: function(th, devices) {
        var me = this;
        var def = new Deferred();
        var width = deviceMap.getSurfaceWidth(me.mapCtx),
            height = deviceMap.getSurfaceHeight(me.mapCtx);

        deviceMap.getDeviceCoordinates({
            context : me.mapCtx,
            th      : th
        }).then(function(result) {
            _.each(result, function(item) {
                devices[item.device].x = (item.x + 20) * width;
                devices[item.device].y = (item.y + 20) * height;
            });

            def.resolve();
        });

        return def;
    },

    _renderSurface: function(node) {
        var me = this;
        var def = new Deferred();

        gfx.createSurface(node, 800, 350).whenLoaded(this, function(surface) {

            on(node, mouse.enter, function(e) {
                me._inSvgContainer = true;
            });

            on(node, mouse.leave, function(e) {
                me._inSvgContainer = false;
            });

            on(node, 'mousemove', function(e) {
                me._mouse.x = e.clientX;
                me._mouse.y = e.clientY;
            });

            me.surface = surface;
            me.mapCtx = new deviceMap.Context({
                surface : surface,
                model : {
                    contextNode : '/webui:webui/data-stores/l3vpnui:static-map',
                    xpathExpr   : 'device',
                    deviceName  : 'name',
                    xName       : 'coord/l3vpnui:x',
                    yName       : 'coord/l3vpnui:y'
                }
            });

            def.resolve();
        });
        return def;
    },

    _surfaceX : function(x) {
        return x - domGeometry.position(this.surface.rawNode).x;
    },

    _surfaceY : function(y) {
        return y - domGeometry.position(this.surface.rawNode).y;
    },

    _getCloseDevice : function(x, y, ignoreDevice) {
        var me = this;
        var ret;

        if (me._mover) {
            _.each(me.devices, function(device) {
                var svg = device.svg;
                if (svg && (device.name !== ignoreDevice)) {
                    var bb = exSvg.getBoundingBox(svg.shape.rawNode);

                    if (exSvg.pointInBoundingBox(me.domNode, x, y, bb)) {
                        ret = device;
                        return false;
                    }
                }
            });
        }

        return ret;
    },

    _isCeDevice : function(device) {
        return device.name.search('ce') === 0;
    },

    _isPeDevice : function(device) {
        return (device !== undefined) && (device.name.search('pe') === 0);
    },

    _renderDevices: function(th) {
        var me = this;
        var deferred = $.Deferred();

        me.surface.clear();
        deviceMap.renderCloud({
            context : me.mapCtx,
            x       : 200,
            y       : 20
        });

        me._getDevicesState(th).done(function() {
            _.each(me.devices, function(dev) {
                if(dev.x && dev.y) {
                    //var nodes = query('g#' + dev.name, me.surface.rawNode);
                    dev.svg = me._renderDevice(th, dev.x, dev.y, dev.name, dev.state);
                }
            });

            deferred.resolve();
        });

        return deferred.promise();
    },

    _getDevicesState : function(th) {
        var deferred = $.Deferred();

        var dh = new DeviceHelper();
        var wa = [];

        function _getState(device) {
            var deferred = $.Deferred();

            dh.getDeviceOperState(th, device.name).done(function(state) {
                device.state = state;
                deferred.resolve();
            }).fail(function(err) {
                deferred.reject(err);
            });

            return deferred.promise();
        }

        _.each(this.devices, function(dev) {
            wa.push(_getState(dev));
        });

        JsonRpcHelper.whenArray(wa).done(function() {
            deferred.resolve();
        });

        return deferred.promise();
    },

    _renderDevice: function(th, x, y, name, deviceState) {
        var me = this;

        return deviceMap.renderDevice({
            context     : this.mapCtx,
            th          : th,
            x           : x,
            y           : y,
            name        : name,
            deviceState : deviceState,
            onMoved     : function(mover, shift) {
               deviceMap.moveDevice({
                   context : me.mapCtx,
                   mover   : mover,
                   shift   : shift
               });
            }
        });
    },

    _renderPaths: function(th) {
        var me = this;
        array.forEach(me._connections, function(con) {
            var e1Dev = me.devices[con.ep1.device];
            var e2Dev = me.devices[con.ep2.device];

            if(('svg' in e1Dev) && ('svg' in e2Dev)) {
                deviceMap.renderDeviceDevicePath({
                    context : me.mapCtx,
                    device1 : e1Dev.svg,
                    device2 : e2Dev.svg,
                    text    : con.name
                });
            }
        });
    },

    _createAddEndpointDialog: function(service, device, row) {
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
        var epInterfaceWidget;

        var dlg = new ModalSchemaDialog({
            title : 'Add Endpoint',

            additionalClass : 'l3vpnui-topology-connection-dialog',

            type    : 'list',
            action  : 'create',

            keypath : '/l3vpn:vpn/l3vpn{' + service + '}/endpoint',
            cols    : 2,

            callbacks : {
                onValidOk : function() {
                   if(!row.isDetailShown()) {
                        row.showDetail();
                   }
                   var details = registry.byId('l3vpnui-service-inline-edit-list-' + service);
                   details.refresh();
                }
            },

            fields  : [{
                leaf : 'id',
                text : 'Id',
                type : {
                    isKey : true
                }
            }, {
                leaf : 'ce-device',
                text : 'Device',
                type : {
                    kind : 'leafref-list',
                    path : '/ncs:devices/device',
                    onChange : function(deviceName) {
                        _updateInterfaceWidget(deviceName, epInterfaceWidget);
                   }
                },
                initialValue: device
            }, {
                leaf : 'ce-interface',
                text : 'Interface',
                createWidget : function(field) {
                    epInterfaceWidget = new Select({
                        ownTitle : false,
                        label    : field.text
                    });
                    return epInterfaceWidget;
                }
            }, {
                leaf : 'ip-network',
                text : 'IP Network'
            }, {
                leaf : 'bandwidth',
                text : 'Bandwidth'
            }, {
                leaf : 'as-number',
                text : 'AS Number'
            }]
        });

        dlg.startup();

        setTimeout(function() {
            // FIXME : Brittle setting of initial interface values
            // Should have a possibility to detect when devices combo values are set

            var dw = dlg.getField('ce-device');

            _updateInterfaceWidget(dw.getValue(), epInterfaceWidget);
        }, 1000);
        return dlg;
    },

    _moveCePeTopologyDialog : function(args) {
        var me = this;

        var ceDevice = args.ceDeviceName;
        var removeConnection;
        var connection;

        // NOTE: Assumes only one ce-pe connection and that it's ep1 that contains the ce name
        _.each(this._connections, function(con) {
            if ((con.ep1.device === args.ceDeviceName) || (con.ep2.device === args.ceDeviceName)) {
                removeConnection = con.name;
                return false;
            }
        });

        if (removeConnection) {
            connection = _.find(this._connections, function(con) {
                return con.name === removeConnection;
            });
        }

        dialogs.moveTopologyConnectionDialog({
            ceDevice : args.ceDeviceName,
            peDevice : args.toPeDeviceName,
            removeConnection : removeConnection,
            connection       : connection,

            callbacks : {
                onValidOk : function() {
                    if (_.isFunction(me.callbacks.topologyUpdated)) {
                        me.callbacks.topologyUpdated();
                    }

                    me.redraw();
                }
            }
        });
    }
});

});

