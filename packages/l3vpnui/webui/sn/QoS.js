define(null, [
    'dojo/_base/declare',
    'dojo/string',

    'dijit/_TemplatedMixin',
    'dijit/layout/ContentPane',

    'tailf/core/logger',
    'tailf/dijit/html',
    'tailf/dijit/schema/List',

    'ncs/path',
    'ncs/Href',

    'dojo/text!./templates/QoS.html'
], function(
    declare,
    dojoString,

    _TemplatedMixin, ContentPane,

    logger,
    dijitHtml, List,

    ncsPath, ncsHref,

    template
) {

var _keypath = '/l3vpn:qos/qos-policy';

return declare([ContentPane, _TemplatedMixin], {
    templateString : template,

    title: 'QoS',
    iconClass: 'icon-desktop',

    destroy : function() {
        this.inherited(arguments);
    },

    postCreate: function() {
        var me = this;

        var list = new List({
            keypath : _keypath,
            keys    : ['name'],
            fields : [{
                name     : 'name',
                text     : 'Name',
                editable : false,

                decorator : function(value) {
                    value = dojoString.escape(value);
                    return dijitHtml.a(
                        ncsHref.getModelHref(ncsPath.path(_keypath, value)),
                        value);
                }
            }]
        });

        me.addChild(list);
    }
});
});

