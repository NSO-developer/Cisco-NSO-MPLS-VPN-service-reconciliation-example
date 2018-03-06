define([
    'tailf/core/logger',
    'tailf/core/happy',
    'tailf/core/protocol/JsonRpcHelper',

    'ncs/path',
    'ncs/services/_Service',
    'ncs/services/_ServiceType',

    'ncs/services/services-config'

], function(
    logger, happy,
    JsonRpcHelper,
    ncsPath,
    _Service, _ServiceType,
    servicesConfig
) {

// -----------------------------------------------------------------------------

function m_keypath(serviceName) {
    return ncsPath.path('/l3vpn:vpn/l3vpn', serviceName);
}


// -----------------------------------------------------------------------------

var L3VpnService = _Service.extend({
    init : function() {
        this._super('l3vpn');
    },

    path : function(serviceName) {
        return m_keypath(serviceName);
    },

    getOneService : function(th, serviceName) {
        var me = this;
        var path = me.path(serviceName);

        return happy(JsonRpcHelper.getValues, th, path, ['device-list'], function(result) {
            return {
                type    : 'l3vpn',
                name    : serviceName,
                devices : result.values[0].value
            };
        });
    }

});

var L3VpnServiceType = _ServiceType.extend({

    init : function() {
        this._super('l3vpn');
    },

    servicePointName : function() {
        return '/l3vpn:vpn/l3vpn';
    },

    serviceClassInstance : function() {
        return new L3VpnService();
    },

    isKeypath : function(kp) {
        return kp.indexOf('/l3vpn:vpn/l3vpn{') === 0;
    },

    keypathToServiceName : function(kp) {
        // /ncs:services/ts1{s-960495}
        var ret = kp.substr(17);
        ret = ret.slice(0, -1);

        return ret;
    },

    serviceNameToKeypath : function(serviceName) {
        return m_keypath(serviceName);
    },

    serviceUI : function() {
        return {
            model : {
                keys : ['name'],
                columns : [{
                    type  : 'leaf',
                    name  : 'name',
                    label : 'Name'
                }, {
                    type  : 'leaf',
                    name  : 'route-distinguisher',
                    label : 'Route Distinguisher'
                }]
            }
        };
    }
});


function m_init() {
    servicesConfig.addServiceType('/l3vpn:vpn/l3vpn', L3VpnServiceType);
}

return {
    keypath : m_keypath,
    init    : m_init
};

});
