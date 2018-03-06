define([
    'dijit/layout/ContentPane',

    'tailf/core/logger',
    'ncs/config',
    'ncs/d3/DeviceServiceNavigation',

    './sn/service-config',
    './sn/MainContentPane',
    './sn/ServiceNodeGraph'
], function(
    ContentPane,

    logger,

    NcsConfig,
    DeviceServiceNavigation,

    serviceConfig,
    MainContentPane,
    ServiceNodeGraph
) {

    function _addViews() {
        NcsConfig.addView('l3vpnui', 'l3vpnui', function() {
            return new MainContentPane();
        });

        NcsConfig.setHomeView('l3vpnui');
    }

    return {
       init : function() {
            _addViews();
            serviceConfig.init();

            DeviceServiceNavigation.config.addServiceNodeGraph(
                    '/l3vpn:vpn/l3vpn', ServiceNodeGraph);
       }
    };
});
