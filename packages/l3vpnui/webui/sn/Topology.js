define(null, [
    'dojo/_base/declare',

    'dijit/_TemplatedMixin',
    'dijit/Dialog',
    'dijit/layout/ContentPane',


    'tailf/core/logger',
    'tailf/dijit/html',
    'tailf/dijit/schema/List',

    'tailf/dijit/dialogs/InlineSchemaDialog',

    'ncs/path',
    'ncs/Href',

    'dojo/text!./templates/Topology.html'
], function(
    declare,
    _TemplatedMixin, Dialog, ContentPane,

    logger,

    dijitHtml, List,

    InlineSchemaDialog,

    ncsPath, ncsHref,

    template
) {

var _keypath = '/l3vpn:topology/connection';

return declare([ContentPane, _TemplatedMixin], {
    templateString : template,

    title: 'Topology',
    iconClass: 'icon-desktop',

    postCreate: function() {
        var me = this;
        me.inherited(arguments);

        var connectionList = new List({
            style : 'height:100%',
            keypath : _keypath,
            keys    : ['name'],

            hasTree : true,

            fields : [{
                name : 'name',
                text : 'Connection',
                decorator : function(value) {
                    return dijitHtml.a(
                        ncsHref.getModelHref(ncsPath.path(_keypath, value)),
                        value);
                }
             }, {
                name : 'link-vlan',
                text : 'Link VLAN',
                editable : true
            }, {
                name : 'endpoint-1/device',
                text : 'EP1 Device',
                editable : true,
                decorator : function(value) {
                    return dijitHtml.a(
                        ncsHref.getModelHref(ncsPath.device(value)),
                        value);
                 }
            }, {
                name : 'endpoint-1/interface',
                text : 'EP1 Interface'
            }, {
                name : 'endpoint-1/ip-address',
                text : 'EP1 Address',
                editable : true
            }, {
                name : 'endpoint-2/device',
                text : 'EP2 Device',
                editable : true,
                decorator : function(value) {
                    return dijitHtml.a(
                        ncsHref.getModelHref(ncsPath.device(value)),
                        value);
               }
             }, {
                name : 'endpoint-2/interface',
                text : 'EP2 Interface'
            }, {
                name : 'endpoint-2/ip-address',
                text : 'EP2 Address',
                editable : true
            }]

/*
            callbacks : {
                renderDetail : function(args) {
                    me._renderListDetail(args);
                }
            }
*/
        });

        me.connectionList = connectionList;
        me.addChild(connectionList);
    },

    refreshList : function() {
        this.connectionList.refresh();
    },

    _renderListDetail : function(args) {

        var cp = new InlineSchemaDialog({
            additionalClass : 'l3vpnui-topology-inline-dialog',

            keypath : args.rowKeypath,
            cols    : 1,
            fields  : [{
                leaf : 'endpoint-1/device',
                text : 'Endpoint 1 Device'
            }, {
                leaf : 'endpoint-1/interface',
                text : 'Endpoint 1 Interface'
            }]
        }, args.node);

        cp.startup();

        args.deferred.callback();

    }
});

});

