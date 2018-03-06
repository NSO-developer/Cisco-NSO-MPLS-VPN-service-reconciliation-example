define([
    'lodash',

    'dojo/_base/declare',
    'dojo/string',
    'dojo/Deferred',

    'dijit/form/Select',

    'tailf/core/protocol/JsonRpc',
    'tailf/core/protocol/JsonRpcHelper'
], function(
    _,

    declare,
    dojoString,
    Deferred,

    Select,

    JsonRpc,
    JsonRpcHelper
){
    return declare('QosSelect', [Select], {
        class: 'l3vpn-service-qos-select',

        constructor: function() {
            this.options = [];
        },
        postCreate : function() {
            this.inherited(arguments);

            var me = this;
            me.service = undefined;

        },

        customApplyEdit: function(service, value) {
            var def = new Deferred();
            var me = this;
            var path = '/l3vpn:vpn/l3vpn{' + service + '}/qos/l3vpn:qos-policy';

            JsonRpcHelper.write().done(function(th) {
                if(value) {
                    JsonRpc('set_value', {th: th, path: path, value: value}).done(function() {
                        me.attr('value', value);
                        def.resolve();
                    });
                } else {
                    JsonRpcHelper.deleteAllowNonExist(th, path).done(function() {
                        def.resolve();
                    });
                }
            });
            return def.promise;
        },

        setDisplayedValue: function(value) {

        },

        refresh: function() {
            var me = this;
            JsonRpcHelper.read().done(function(th) {
                me._currentQos(th, me.service).then(function(current) {
                    me.attr('value', current);
                });
            });
        },

        loadOptions: function(service) {
            var me = this;

            me.service = service;

            JsonRpcHelper.read().done(function(th) {
                me._queryQos(th).then(function(qoss) {
                    var options = [{label: '&nbsp;', value: ''}];
                    _.each(qoss, function(qos) {
                        options.push({label: qos, value: qos});
                    });
                    me.options = options;

                    me._currentQos(th, service).then(function(current) {
                        me.attr('value', current);
                    });
                });
            });
        },

        _queryQos: function(th) {
            var def = new Deferred();
            var qoss = [];

            JsonRpcHelper.getListKeys(th, '/l3vpn:qos/qos-policy', true).done(function(res) {
                _.each(res, function(item) {
                    var val = dojoString.escape(item[0]);
                    qoss.push(val);
                });
                def.resolve(qoss);
            });
            return def;
        },
        _currentQos: function(th, service) {
            var def = new Deferred();
            var path = '/l3vpn:vpn/l3vpn{' + service + '}/qos',
                leafs = ['qos-policy'];

            function getValue(res) {
                var value;
                if(res.values && res.values.length > 0) {
                    value = res.values[0].value;
                }
                return value;
            }

            if(service) {
                JsonRpcHelper.getValues(th, path, leafs).done(function(res) {
                    def.resolve(getValue(res));
                });
            } else {
                def.resolve('');
            }
            return def;
        }
    });
});
